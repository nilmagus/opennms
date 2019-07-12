/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
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

package org.opennms.core.ipc.rpc.kafka.server;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.joda.time.Duration;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.common.kafka.KafkaConfigProvider;
import org.opennms.core.ipc.common.kafka.Utils;
import org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.core.tracing.api.TracerRegistry;
import org.opennms.distributed.core.api.MinionIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import com.swrve.ratelimitedlogger.RateLimitedLog;

/**
 * This Manager runs on Minion, A consumer thread (@{@link KafkaRpcHandler} will be started on each RPC module which handles the request and
 * executes it on rpc module and sends the response to Kafka. When the request is directed at specific minion
 * (request with system-id), minion executes the request only if system-id matches with minionId.
 */
public class KafkaRpcServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcServerManager.class);
    private static final RateLimitedLog RATE_LIMITED_LOG = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.standardSeconds(30))
            .build();
    private final Map<String, RpcModule<RpcRequest, RpcResponse>> registerdModules = new ConcurrentHashMap<>();
    private final Properties kafkaConfig = new Properties();
    private final KafkaConfigProvider kafkaConfigProvider;
    private KafkaProducer<String, byte[]> producer;
    private MinionIdentity minionIdentity;
    private Integer maxBufferSize = KafkaRpcConstants.MAX_BUFFER_SIZE_CONFIGURED;
    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                                                       .setNameFormat("rpc-server-kafka-consumer-%d")
                                                       .build();
    private final ExecutorService executor = Executors.newCachedThreadPool(threadFactory);
    private Map<RpcModule<RpcRequest, RpcResponse>, KafkaRpcHandler> rpcModuleConsumers = new ConcurrentHashMap<>();
    // cache to hold rpcId and ByteString when there are multiple chunks for the message.
    private Map<String, ByteString> largeMessageCache = new ConcurrentHashMap<>();
    // Delay queue which caches rpcId and removes when rpcId reaches expiration time.
    private DelayQueue<RpcId> rpcIdQueue = new DelayQueue<>();
    private ExecutorService delayQueueExecutor = Executors.newSingleThreadExecutor();
    private final TracerRegistry tracerRegistry;

    public KafkaRpcServerManager(KafkaConfigProvider configProvider, MinionIdentity minionIdentity, TracerRegistry tracerRegistry) {
        this.kafkaConfigProvider = configProvider;
        this.minionIdentity = minionIdentity;
        this.tracerRegistry = tracerRegistry;
    }

    public void init() throws IOException {
        // group.id is mapped to minion location, so one of the minion executes the request.
        kafkaConfig.put(ConsumerConfig.GROUP_ID_CONFIG, minionIdentity.getLocation());
        kafkaConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());
        kafkaConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getCanonicalName());
        kafkaConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getCanonicalName());
        kafkaConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getCanonicalName());
        // Retrieve all of the properties from org.opennms.core.ipc.rpc.kafka.cfg
        kafkaConfig.putAll(kafkaConfigProvider.getProperties());
        LOG.info("initializing the Kafka producer with: {}", kafkaConfig);
        producer = Utils.runWithGivenClassLoader(() -> new KafkaProducer<String, byte[]>(kafkaConfig), KafkaProducer.class.getClassLoader());
        // Configurable cache config.
        maxBufferSize = KafkaRpcConstants.getMaxBufferSize(kafkaConfig);
        // Thread to expire RpcId from rpcIdQueue.
        delayQueueExecutor.execute(() -> {
            while(true) {
                try {
                    RpcId rpcId = rpcIdQueue.take();
                    largeMessageCache.remove(rpcId.getRpcId());
                } catch (InterruptedException e) {
                    LOG.error("Delay Queue has been interrupted ", e);
                    break;
                }
            }
        });
        tracerRegistry.init(minionIdentity.getLocation() + "@" + minionIdentity.getId());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void bind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            if (registerdModules.containsKey(rpcModule.getId())) {
                LOG.warn(" {} module is already registered", rpcModule.getId());
            } else {
                registerdModules.put(rpcModule.getId(), rpcModule);
                startConsumerForModule(rpcModule);
            }
        }
    }

    private void startConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(KafkaRpcConstants.RPC_REQUEST_TOPIC_NAME, rpcModule.getId(),
                minionIdentity.getLocation());
        KafkaConsumer<String, byte[]> consumer = Utils.runWithGivenClassLoader(() -> new KafkaConsumer<>(kafkaConfig), KafkaConsumer.class.getClassLoader());
        KafkaRpcHandler kafkaRpcHandler = new KafkaRpcHandler(this, rpcModule, consumer, topicNameFactory.getName());
        executor.execute(kafkaRpcHandler);
        LOG.info("started kafka consumer for module : {}", rpcModule.getId());
        rpcModuleConsumers.put(rpcModule, kafkaRpcHandler);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void unbind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            registerdModules.remove(rpcModule.getId());
            stopConsumerForModule(rpcModule);
        }
    }

    private void stopConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        KafkaRpcHandler kafkaConsumerRunner  = rpcModuleConsumers.remove(rpcModule);
        LOG.info("stopped kafka consumer for module : {}", rpcModule.getId());
        kafkaConsumerRunner.shutdown();
    }

    public void destroy() {
        if (producer != null) {
            producer.close();
        }
         largeMessageCache.clear();
         executor.shutdown();
         delayQueueExecutor.shutdown();
    }


    public DelayQueue<RpcId> getRpcIdQueue() {
        return rpcIdQueue;
    }


    public KafkaProducer<String, byte[]> getProducer() {
        return producer;
    }

    public MinionIdentity getMinionIdentity() {
        return minionIdentity;
    }

    public Map<String, ByteString> getLargeMessageCache() {
        return largeMessageCache;
    }

    public TracerRegistry getTracerRegistry() {
        return tracerRegistry;
    }
}