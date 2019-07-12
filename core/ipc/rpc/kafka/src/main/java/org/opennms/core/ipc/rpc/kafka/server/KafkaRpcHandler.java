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

package org.opennms.core.ipc.rpc.kafka.server;

import static org.opennms.core.tracing.api.TracerConstants.TAG_LOCATION;
import static org.opennms.core.tracing.api.TracerConstants.TAG_RPC_FAILED;
import static org.opennms.core.tracing.api.TracerConstants.TAG_SYSTEM_ID;

import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.joda.time.Duration;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants;
import org.opennms.core.ipc.rpc.kafka.model.RpcMessageProtos;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.math.IntMath;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.swrve.ratelimitedlogger.RateLimitedLog;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtractAdapter;

/**
 * This runs on Minion.
 * There is one thread for each module. It consumes from the specific topic for each module, sends the request
 * asynchronously to module, once it gets response, send the response back to kafka.
 * This also handles large buffer (combines each message chunk and sends the request to module)
 * This also handles directed RPC (RPC that should only be consumed on specific minion that matches systemId)
 */
public class KafkaRpcHandler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcHandler.class);
    private static final RateLimitedLog RATE_LIMITED_LOG = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.standardSeconds(30))
            .build();
    private Integer maxBufferSize = KafkaRpcConstants.MAX_BUFFER_SIZE_CONFIGURED;
    private KafkaRpcServerManager rpcServerManager;
    private final KafkaConsumer<String, byte[]> consumer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private String topic;
    private RpcModule<RpcRequest, RpcResponse> module;


    public KafkaRpcHandler(KafkaRpcServerManager kafkaRpcServerManager,
                           RpcModule<RpcRequest, RpcResponse> rpcModule,
                           KafkaConsumer<String, byte[]> consumer,
                           String topic) {
        this.rpcServerManager = kafkaRpcServerManager;
        this.consumer = consumer;
        this.topic = topic;
        this.module = rpcModule;
    }

    public void shutdown() {
        closed.set(true);
        consumer.wakeup();
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Arrays.asList(topic));
            LOG.info("subscribed to topic {}", topic);
            while (!closed.get()) {
                // Wait till something is available.
                ConsumerRecords<String, byte[]> records = consumer.poll(java.time.Duration.ofMillis(Long.MAX_VALUE));
                for (ConsumerRecord<String, byte[]> record : records) {
                    try {
                        RpcMessageProtos.RpcMessage rpcMessage = RpcMessageProtos.RpcMessage
                                .parseFrom(record.value());
                        String rpcId = rpcMessage.getRpcId();
                        long expirationTime = rpcMessage.getExpirationTime();
                        if (expirationTime < System.currentTimeMillis()) {
                            LOG.warn("ttl already expired for the request id = {}, won't process.", rpcMessage.getRpcId());
                            continue;
                        }
                        boolean hasSystemId = !Strings.isNullOrEmpty(rpcMessage.getSystemId());
                        String minionId = rpcServerManager.getMinionIdentity().getId();
                        if (hasSystemId && !(minionId.equals(rpcMessage.getSystemId()))) {
                            // directed RPC and not directed at this minion
                            continue;
                        }
                        if (hasSystemId) {
                            // directed RPC, there may be more than one request with same request Id, cache and allow only one.
                            String messageId = rpcId;
                            // If this message has more than one chunk, chunk number should be added to messageId to make it unique.
                            if (rpcMessage.getTotalChunks() > 1) {
                                messageId = messageId + rpcMessage.getCurrentChunkNumber();
                            }
                            // If rpcId is already present in queue, no need to process it again.
                            DelayQueue<RpcId> rpcIdQueue = rpcServerManager.getRpcIdQueue();
                            if (rpcIdQueue.contains(new RpcId(messageId, rpcMessage.getExpirationTime())) ||
                                    rpcMessage.getExpirationTime() < System.currentTimeMillis()) {
                                continue;
                            } else {
                                rpcIdQueue.offer(new RpcId(messageId, rpcMessage.getExpirationTime()));
                            }
                        }
                        ByteString rpcContent = rpcMessage.getRpcContent();
                        // For larger messages which get split into multiple chunks, cache them until all of them arrive.
                        if (rpcMessage.getTotalChunks() > 1) {
                            Map<String, ByteString> messageCache = rpcServerManager.getLargeMessageCache();
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
                            messageCache.remove(rpcId);
                        }
                        //Build child span from rpcMessage.
                        Tracer.SpanBuilder spanBuilder = buildSpanFromRpcMessage(rpcMessage);
                        // Start minion span.
                        Span minionSpan = spanBuilder.start();
                        // Retrieve custom tags from rpcMessage and add them as tags.
                        rpcMessage.getTracingInfoList().forEach(tracingInfo -> {
                            minionSpan.setTag(tracingInfo.getKey(), tracingInfo.getValue());
                        });
                        RpcRequest request = module.unmarshalRequest(rpcContent.toStringUtf8());
                        // Set tags for minion span
                        minionSpan.setTag(TAG_LOCATION, request.getLocation());
                        if(request.getSystemId() != null) {
                            minionSpan.setTag(TAG_SYSTEM_ID, request.getSystemId());
                        }
                        CompletableFuture<RpcResponse> future = module.execute(request);
                        future.whenComplete((res, ex) -> {
                            final RpcResponse response;
                            if (ex != null) {
                                // An exception occurred, store the exception in a new response
                                LOG.warn("An error occured while executing a call in {}.", module.getId(), ex);
                                response = module.createResponseWithException(ex);
                                minionSpan.log(ex.getMessage());
                                minionSpan.setTag(TAG_RPC_FAILED, "true");
                            } else {
                                // No exception occurred, use the given response
                                response = res;
                            }
                            // Finish minion Span
                            minionSpan.finish();
                            try {
                                final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(KafkaRpcConstants.RPC_RESPONSE_TOPIC_NAME,
                                        module.getId());
                                final String responseAsString = module.marshalResponse(response);
                                final byte[] messageInBytes = responseAsString.getBytes();
                                int totalChunks = IntMath.divide(messageInBytes.length, maxBufferSize, RoundingMode.UP);
                                // Divide the message in chunks and send each chunk as a different message with the same key.
                                RpcMessageProtos.RpcMessage.Builder builder = RpcMessageProtos.RpcMessage.newBuilder()
                                        .setRpcId(rpcId);
                                builder.setTotalChunks(totalChunks);
                                for (int chunk = 0; chunk < totalChunks; chunk++) {
                                    // Calculate remaining bufferSize for each chunk.
                                    int bufferSize = KafkaRpcConstants.getBufferSize(messageInBytes.length, maxBufferSize, chunk);
                                    ByteString byteString = ByteString.copyFrom(messageInBytes, chunk * maxBufferSize, bufferSize);
                                    RpcMessageProtos.RpcMessage rpcResponse = builder.setCurrentChunkNumber(chunk)
                                            .setRpcContent(byteString)
                                            .build();
                                    final ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
                                            topicNameFactory.getName(), rpcId, rpcResponse.toByteArray());
                                    int chunkNum = chunk;
                                    rpcServerManager.getProducer().send(producerRecord, (recordMetadata, e) -> {
                                        if (e != null) {
                                            RATE_LIMITED_LOG.error(" RPC response {} with id {} couldn't be sent to Kafka", rpcResponse, rpcId, e);
                                        } else {
                                            if (LOG.isTraceEnabled()) {
                                                LOG.trace("request with id {} executed, sending response {}, chunk number {} ", rpcId, responseAsString, chunkNum);
                                            }
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                LOG.error("Marshalling response in RPC module {} failed.", module, t);
                            }
                        });
                    } catch (InvalidProtocolBufferException e) {
                        LOG.error("error while parsing the request", e);
                    }
                }
            }
        } catch (WakeupException e) {
            // Ignore exception if closing
            if (!closed.get()) {
                throw e;
            }
        } finally {
            consumer.close();
        }
    }

    private Tracer.SpanBuilder buildSpanFromRpcMessage( RpcMessageProtos.RpcMessage rpcMessage) {
        // Initializer tracer and extract parent tracer context from TracingInfo
        final Tracer tracer = rpcServerManager.getTracerRegistry().getTracer();
        Tracer.SpanBuilder spanBuilder;
        Map<String, String> tracingInfoMap = new HashMap<>();
        rpcMessage.getTracingInfoList().forEach(tracingInfo -> {
            tracingInfoMap.put(tracingInfo.getKey(), tracingInfo.getValue());
        });
        SpanContext context = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(tracingInfoMap));
        if (context != null) {
            spanBuilder = tracer.buildSpan(module.getId()).asChildOf(context);
        } else {
            spanBuilder = tracer.buildSpan(module.getId());
        }
        return spanBuilder;
    }

}
