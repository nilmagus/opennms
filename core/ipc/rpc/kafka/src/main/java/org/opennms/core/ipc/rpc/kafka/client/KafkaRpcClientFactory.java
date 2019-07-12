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


package org.opennms.core.ipc.rpc.kafka.client;

import static org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants.DEFAULT_TTL;
import static org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants.MAX_BUFFER_SIZE;
import static org.opennms.core.tracing.api.TracerConstants.TAG_LOCATION;
import static org.opennms.core.tracing.api.TracerConstants.TAG_SYSTEM_ID;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.joda.time.Duration;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.common.kafka.KafkaConfigProvider;
import org.opennms.core.ipc.common.kafka.OnmsKafkaConfigProvider;
import org.opennms.core.ipc.rpc.kafka.KafkaRpcConstants;
import org.opennms.core.ipc.rpc.kafka.ResponseCallback;
import org.opennms.core.ipc.rpc.kafka.model.RpcMessageProtos;
import org.opennms.core.logging.Logging;
import org.opennms.core.logging.Logging.MDCCloseable;
import org.opennms.core.rpc.api.RpcClient;
import org.opennms.core.rpc.api.RpcClientFactory;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.core.tracing.api.TracerRegistry;
import org.opennms.core.tracing.util.TracingInfoCarrier;
import org.opennms.core.utils.SystemInfoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.math.IntMath;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import com.swrve.ratelimitedlogger.RateLimitedLog;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;

/**
 * This Client Factory runs on OpenNMS. Whenever a Client receives an RPC request, it generates a unique rpcId and
 * initiates a response handler unique to the rpcId. It also starts a consumer thread for the specific RPC module if it
 * doesn't exist yet.
 * <p>
 * The client also expands the buffer into chunks if it is larger than the configured buffer size. The client then sends
 * the request to Kafka. If it is directed RPC (to a specific minion) it sends the request to all partitions there by to
 * all consumers (minions).
 * <p>
 * Consumer thread (one for each module) will receive the response and send it to a response handler which will return
 * the response.
 * <p>
 * Timeout tracker thread will fetch the response handler from it's delay queue and send a timeout response if it is not
 * finished already.
 */
public class KafkaRpcClientFactory implements RpcClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcClientFactory.class);
    private static final RateLimitedLog RATE_LIMITED_LOG = RateLimitedLog
            .withRateLimit(LOG)
            .maxRate(5).every(Duration.standardSeconds(30))
            .build();
    private String location;
    private KafkaProducer<String, byte[]> producer;
    private final Properties kafkaConfig = new Properties();
    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("rpc-client-kafka-consumer-%d")
            .build();
    private final ThreadFactory timerThreadFactory = new ThreadFactoryBuilder()
            .setNameFormat("rpc-client-timeout-tracker-%d")
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
    private final ExecutorService timerExecutor = Executors.newSingleThreadExecutor(timerThreadFactory);
    private final Map<String, ResponseCallback> rpcResponseMap = new ConcurrentHashMap<>();
    private KafkaRpcResponseConsumer kafkaRpcResponseConsumer;
    private DelayQueue<ResponseCallback> delayQueue = new DelayQueue<>();
    // Used to cache responses when large message are involved.
    private Map<String, ByteString> messageCache = new ConcurrentHashMap<>();
    private MetricRegistry metrics = new MetricRegistry();
    private JmxReporter metricsReporter = null;


    @Autowired
    private TracerRegistry tracerRegistry;
    private Tracer tracer;

    @Override
    public <S extends RpcRequest, T extends RpcResponse> RpcClient<S, T> getClient(RpcModule<S, T> module) {

        final KafkaRpcClientFactory clientFactory = this;
        return new RpcClient<S, T>() {

            @Override
            public CompletableFuture<T> execute(S request) {

                // The request is for the current location, invoke it directly
                if (request.getLocation() == null || request.getLocation().equals(location)) {
                    return module.execute(request);
                }

                Span span = startSpanAndAddTags(request);
                // Generate topic name from the module id and location.
                final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory(
                        KafkaRpcConstants.RPC_REQUEST_TOPIC_NAME, module.getId(), request.getLocation());
                String requestTopic = topicNameFactory.getName();
                // Marshal request and convert it to bytes.
                byte[] messageInBytes = module.marshalRequest(request).getBytes();
                // Generate RPC Id for every request to track request/response.
                String rpcId = UUID.randomUUID().toString();
                // Set expiration time for curent request based on ttl.
                long expirationTime = System.currentTimeMillis() + getTTL(request);

                // Create a future and add it to response handler which will complete the future when it receives callback.
                final CompletableFuture<T> future = new CompletableFuture<>();
                final Map<String, String> loggingContext = Logging.getCopyOfContextMap();
                KafkaRpcResponseHandler<S, T> responseHandler = new KafkaRpcResponseHandler<S, T>(clientFactory, future, module, request.getLocation(), rpcId,
                        expirationTime, loggingContext, span);
                delayQueue.offer(responseHandler);
                rpcResponseMap.put(rpcId, responseHandler);

                // Start consumer if it is not already started.
                kafkaRpcResponseConsumer.startConsumingForModule(module.getId());

                int totalChunks = IntMath.divide(messageInBytes.length, MAX_BUFFER_SIZE, RoundingMode.UP);
                RpcMessageProtos.RpcMessage.Builder builder = RpcMessageProtos.RpcMessage.newBuilder()
                        .setRpcId(rpcId)
                        .setSystemId(request.getSystemId() == null ? "" : request.getSystemId())
                        .setExpirationTime(expirationTime);

                // Divide the message in chunks and send each chunk as a different message with the same key.
                for (int chunk = 0; chunk < totalChunks; chunk++) {
                    // Calculate remaining bufferSize for each chunk.
                    int bufferSize = KafkaRpcConstants.getBufferSize(messageInBytes.length, MAX_BUFFER_SIZE, chunk);
                    ByteString byteString = ByteString.copyFrom(messageInBytes, chunk * MAX_BUFFER_SIZE, bufferSize);
                    // Add tracing info to message builder.
                    addTracingInfoToRpcMessage(request, span, builder);
                    // Build message.
                    RpcMessageProtos.RpcMessage rpcMessageChunk = builder.setRpcContent(byteString)
                            .setCurrentChunkNumber(chunk)
                            .setTotalChunks(totalChunks)
                            .build();
                    // Initialize kafka producer callback.
                    Callback callback = initializeCallback(future, request, rpcId, chunk);
                    // Send message chunk to kafka.
                    sendRpcChunkToKafka(rpcMessageChunk, callback, request.getSystemId(), rpcId, requestTopic);
                }
                // Update JMX metrics.
                updateMetrics(request.getLocation(), messageInBytes.length);
                return future;
            }

            /**
             * Send the this message chunk to kafka.
             *
             * @param rpcMessageChunk  message chunk
             * @param callback    callback to trace or complete the request if there is an exception.
             * @param systemId    system id for the current request
             * @param rpcId       unique Id for the current rpc request.
             * @param requestTopic  request topic the current request is going to.
             */
            private void sendRpcChunkToKafka(RpcMessageProtos.RpcMessage rpcMessageChunk, Callback callback,
                                             String systemId, String rpcId, String requestTopic) {
                if (systemId != null) {
                    // For directed RPCs, send request to all partitions (consumers),
                    // as it is reasonable to assume that partitions >= consumers(number of minions at location).
                    List<PartitionInfo> partitionInfo = producer.partitionsFor(requestTopic);
                    partitionInfo.forEach(partition -> {
                        // Use rpc Id as key.
                        final ProducerRecord<String, byte[]> record = new ProducerRecord<>(requestTopic,
                                partition.partition(), rpcId, rpcMessageChunk.toByteArray());
                        producer.send(record, callback);
                    });
                } else {
                    // Use rpc Id as key.
                    final ProducerRecord<String, byte[]> record = new ProducerRecord<>(requestTopic,
                            rpcId, rpcMessageChunk.toByteArray());
                    producer.send(record, callback);
                }
            }

            private Callback initializeCallback(CompletableFuture<T> future, S request, String rpcId, int chunkNum) {
                return (recordMetadata, e) -> {
                    if (e != null) {
                        RATE_LIMITED_LOG.error(" RPC request {} with id {} couldn't be sent to Kafka", request, rpcId, e);
                        future.completeExceptionally(e);
                    } else {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("RPC Request {} with id {} chunk {} sent to minion at location {}", request, rpcId, chunkNum, request.getLocation());
                        }
                    }
                };
            }

            /**
             *  Start span for the current request and set tags.
             * @param request  RPC request
             * @return span  generated span for the current request
             */
            private Span startSpanAndAddTags(S request) {
                if (tracer == null) {
                    return null;
                }
                Span span = tracer.buildSpan(module.getId()).start();
                span.setTag(TAG_LOCATION, request.getLocation());
                if (request.getSystemId() != null) {
                    span.setTag(TAG_SYSTEM_ID, request.getSystemId());
                }
                // Set custom tags on this span.
                request.getTracingInfo().forEach(span::setTag);
                return span;
            }


            private void addTracingInfoToRpcMessage(S request, Span span, RpcMessageProtos.RpcMessage.Builder builder) {
                if (span == null) {
                    return;
                }
                TracingInfoCarrier tracingInfoCarrier = new TracingInfoCarrier();
                tracer.inject(span.context(), Format.Builtin.TEXT_MAP, tracingInfoCarrier);
                if (tracingInfoCarrier.getTracingInfoMap().size() > 0) {
                    tracingInfoCarrier.getTracingInfoMap().forEach((key, value) -> {
                        RpcMessageProtos.TracingInfo tracingInfo = RpcMessageProtos.TracingInfo.newBuilder()
                                .setKey(key)
                                .setValue(value).build();
                        builder.addTracingInfo(tracingInfo);
                    });
                }
                //Add custom tags from the request to RPC message so that they can be applied at minion end.
                request.getTracingInfo().forEach((key, value) -> {
                    RpcMessageProtos.TracingInfo tracingInfo = RpcMessageProtos.TracingInfo.newBuilder()
                            .setKey(key)
                            .setValue(value).build();
                    builder.addTracingInfo(tracingInfo);
                });
            }


            private void updateMetrics(String location, int messageLength) {
                final Meter requestSentMeter = metrics.meter(MetricRegistry.name(location, module.getId(), RPC_COUNT));
                requestSentMeter.mark();
                final Histogram rpcRequestSize = metrics.histogram(MetricRegistry.name(location, module.getId(), RPC_REQUEST_SIZE));
                rpcRequestSize.update(messageLength);
            }

            /**
             * Calculate timeout based on ttl and defaul timeout
             * @param request  RPC request
             * @return  ttl , time to live for the current request
             */
            private Long getTTL(S request) {
                Long ttl = request.getTimeToLiveMs();
                ttl = (ttl != null && ttl > 0) ? ttl : DEFAULT_TTL;
                return ttl;
            }
        };

    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setTracerRegistry(TracerRegistry tracerRegistry) {
        this.tracerRegistry = tracerRegistry;
    }

    public TracerRegistry getTracerRegistry() {
        return tracerRegistry;
    }



    public void start() {
        try (MDCCloseable mdc = Logging.withPrefixCloseable(RpcClientFactory.LOG_PREFIX)) {
            // Set the defaults
            kafkaConfig.clear();
            kafkaConfig.put(ConsumerConfig.GROUP_ID_CONFIG, SystemInfoUtils.getInstanceId());
            kafkaConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());
            kafkaConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getCanonicalName());
            kafkaConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getCanonicalName());
            kafkaConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getCanonicalName());

            // Find all of the system properties that start with 'org.opennms.core.ipc.rpc.kafka.' and add them to the config.
            KafkaConfigProvider kafkaConfigProvider = new OnmsKafkaConfigProvider(KafkaRpcConstants.KAFKA_CONFIG_SYS_PROP_PREFIX);
            kafkaConfig.putAll(kafkaConfigProvider.getProperties());
            producer = new KafkaProducer<>(kafkaConfig);
            LOG.info("initializing the Kafka producer with: {}", kafkaConfig);
            // Start consumer which handles all the responses.
            KafkaConsumer<String, byte[]> kafkaConsumer = new KafkaConsumer<>(kafkaConfig);
            kafkaRpcResponseConsumer = new KafkaRpcResponseConsumer(this, kafkaConsumer);
            executor.execute(kafkaRpcResponseConsumer);
            // Initialize metrics reporter.
            metricsReporter = JmxReporter.forRegistry(metrics).
                    inDomain(JMX_DOMAIN_RPC).build();
            metricsReporter.start();
            // Initialize tracer from tracer registry.
            getTracerRegistry().init(SystemInfoUtils.getInstanceId());
            tracer = getTracerRegistry().getTracer();
            LOG.info("started  kafka consumer with : {}", kafkaConfig);
            // Start a new thread which handles timeouts from delayQueue and calls response callback.
            timerExecutor.execute(() -> {
                while (true) {
                    try {
                        ResponseCallback responseCb = delayQueue.take();
                        if (!responseCb.isProcessed()) {
                            LOG.warn("RPC request with id {} timedout ", responseCb.getRpcId());
                            responseCb.sendResponse(null);
                        }
                    } catch (InterruptedException e) {
                        LOG.info("interrupted while waiting for an element from delayQueue", e);
                        break;
                    } catch (Exception e) {
                        LOG.warn("error while sending response from timeout handler", e);
                    }
                }
            });
            LOG.info("started timeout tracker");
        }
    }

    public void stop() {
        LOG.info("stop kafka consumer runner");
        if (metricsReporter != null) {
            metricsReporter.close();
        }
        kafkaRpcResponseConsumer.stop();
        executor.shutdown();
        timerExecutor.shutdown();
    }

    Map<String, ResponseCallback> getRpcResponseMap() {
        return rpcResponseMap;
    }

    Map<String, ByteString> getMessageCache() {
        return messageCache;
    }

    public MetricRegistry getMetrics() {
        return metrics;
    }
}
