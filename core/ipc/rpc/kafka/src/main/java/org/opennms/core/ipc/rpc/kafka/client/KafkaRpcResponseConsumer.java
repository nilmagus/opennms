/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.core.ipc.rpc.kafka.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants;
import org.opennms.core.ipc.rpc.kafka.ResponseCallback;
import org.opennms.core.ipc.rpc.kafka.model.RpcMessageProtos;
import org.opennms.core.logging.Logging;
import org.opennms.core.rpc.api.RpcClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;


/**
 *  This runs on Client side, i.e. OpenNMS.
 *  Kafka Consumer thread that consumes responses from response topic (from minion) and send it to response handler.
 *  There is only one consumer that tracks all the responses. It just unwraps the rpc message from protobuf and sends
 *  actual rpc message to @{@link KafkaRpcResponseHandler}.
 *
 *  This consumer also handles topics being added dynamically to the list of topics that consumer can subscribe to.
 *
 */
public class KafkaRpcResponseConsumer implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcResponseConsumer.class);
    private final KafkaConsumer<String, byte[]> consumer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<String> moduleIdsForTopics = new HashSet<>();
    private final Set<String> topics = new HashSet<>();
    private final AtomicBoolean topicAdded = new AtomicBoolean(false);
    private final CountDownLatch firstTopicAdded = new CountDownLatch(1);

    private KafkaRpcClientFactory clientFactory;

    public KafkaRpcResponseConsumer(KafkaRpcClientFactory kafkaRpcClientFactory, KafkaConsumer<String, byte[]> consumer) {
        this.clientFactory = kafkaRpcClientFactory;
        this.consumer = consumer;
    }

    @Override
    public void run() {
        Logging.putPrefix(RpcClientFactory.LOG_PREFIX);
        if (topics.isEmpty()) {
            // kafka consumer needs to subscribe to at least one topic for the consumer.poll to work.
            while (!topicAdded.get()) {
                try {
                    firstTopicAdded.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    LOG.info("Interrupted before first topic was added. Terminating Kafka RPC consumer thread.");
                    return;
                }
            }
            LOG.info("First topic is added, consumer will be started.");
        }
        while (!closed.get()) {
            if (topicAdded.get()) {
                synchronized (topics) {
                    // Topic subscriptions are not incremental. This list will replace the current assignment (if there is one).
                    LOG.info("Subscribing Kafka RPC consumer to topics named: {}", topics);
                    consumer.subscribe(topics);
                    topicAdded.set(false);
                }
            }
            try {
                // Wait till something is available.
                ConsumerRecords<String, byte[]> records = consumer.poll(java.time.Duration.ofMillis(Long.MAX_VALUE));
                for (ConsumerRecord<String, byte[]> record : records) {
                    // Get Response callback from key and send rpc content to callback.
                    ResponseCallback responseCb = clientFactory.getRpcResponseMap().get(record.key());
                    if (responseCb != null) {
                        RpcMessageProtos.RpcMessage rpcMessage = RpcMessageProtos.RpcMessage
                                .parseFrom(record.value());
                        ByteString rpcContent = rpcMessage.getRpcContent();
                        // For larger messages which get split into multiple chunks, cache them until all of them arrive.
                        if (rpcMessage.getTotalChunks() > 1) {
                            String rpcId = rpcMessage.getRpcId();
                            Map<String, ByteString> messageCache = clientFactory.getMessageCache();
                            ByteString byteString = messageCache.get(rpcId);
                            if (byteString != null) {
                                messageCache.put(rpcId, byteString.concat(rpcMessage.getRpcContent()));
                            } else {
                                messageCache.put(rpcId, rpcMessage.getRpcContent());
                            }
                            if (rpcMessage.getTotalChunks() != rpcMessage.getCurrentChunkNumber() + 1) {
                                continue;
                            }
                            rpcContent = messageCache.get(rpcId);
                        }
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Received RPC response for id {}", rpcMessage.getRpcId());
                        }
                        responseCb.sendResponse(rpcContent.toStringUtf8());
                    } else {
                        LOG.warn("Received a response for request with ID:{}, but no outstanding request was found with this id, The request may have timed out.",
                                record.key());
                    }
                }
            } catch (InvalidProtocolBufferException e) {
                LOG.error("error while parsing response", e);
            } catch (WakeupException e) {
                LOG.info(" consumer got wakeup exception, closed = {} ", closed.get(), e);
            }
        }
        // Close consumer when while loop is closed.
        consumer.close();
    }

    public void stop() {
        closed.set(true);
        consumer.wakeup();
    }

    void startConsumingForModule(String moduleId) {
        if (moduleIdsForTopics.contains(moduleId)) {
            return;
        }
        moduleIdsForTopics.add(moduleId);
        final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(KafkaRpcConstants.RPC_RESPONSE_TOPIC_NAME, moduleId);
        synchronized (topics) {
            if (topics.add(topicNameFactory.getName())) {
                topicAdded.set(true);
                firstTopicAdded.countDown();
                consumer.wakeup();
            }
        }
    }


}
