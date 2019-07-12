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

import static org.opennms.core.rpc.api.RpcClientFactory.RPC_DURATION;
import static org.opennms.core.rpc.api.RpcClientFactory.RPC_FAILED;
import static org.opennms.core.rpc.api.RpcClientFactory.RPC_RESPONSE_SIZE;
import static org.opennms.core.tracing.api.TracerConstants.TAG_TIMEOUT;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.opennms.core.ipc.rpc.kafka.ResponseCallback;
import org.opennms.core.logging.Logging;
import org.opennms.core.rpc.api.RemoteExecutionException;
import org.opennms.core.rpc.api.RequestTimedOutException;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

import io.opentracing.Span;

/**
 * This runs on Client side, i.e. OpenNMS.
 * Handle Response from kafka consumer and timeout thread.
 * This object is created for every request and only lives for the duration of the request.
 * //Successful response
 * When @{@link KafkaRpcResponseConsumer} receives a message from kafka, it checks if there is corresponding
 * response handler for specific rpcId and calls sendResponse on this object.
 * // Timeout response
 * This object is also added to a timeout thread where the objects will be removed when the object reaches timeout.
 * When timeout is reached, timeout thread calls sendResponse on this object which should send the response (timeout).
 *
 **/

public class KafkaRpcResponseHandler<S extends RpcRequest, T extends RpcResponse> implements ResponseCallback {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcResponseHandler.class);

    private KafkaRpcClientFactory clientFactory;
    private final CompletableFuture<T> responseFuture;
    private final RpcModule<S, T> rpcModule;
    private final long expirationTime;
    private final String rpcId;
    private Map<String, String> loggingContext;
    private boolean isProcessed = false;
    private Span span;
    private final Long requestCreationTime;
    private final Histogram rpcDuration;
    private final Meter failedMeter;
    private final Histogram responseSize;

    public KafkaRpcResponseHandler(KafkaRpcClientFactory rpcClientFactory,
                                   CompletableFuture<T> responseFuture,
                                   RpcModule<S, T> rpcModule,
                                   String location,
                                   String rpcId,
                                   long timeout,
                                   Map<String, String> loggingContext,
                                   Span span) {
        this.clientFactory = rpcClientFactory;
        this.responseFuture = responseFuture;
        this.rpcModule = rpcModule;
        this.expirationTime = timeout;
        this.rpcId = rpcId;
        this.loggingContext = loggingContext;
        this.span = span;
        requestCreationTime = System.currentTimeMillis();
        rpcDuration = clientFactory.getMetrics().histogram(MetricRegistry.name(location, rpcModule.getId(), RPC_DURATION));
        failedMeter = clientFactory.getMetrics().meter(MetricRegistry.name(location, rpcModule.getId(), RPC_FAILED));
        responseSize = clientFactory.getMetrics().histogram(MetricRegistry.name(location, rpcModule.getId(), RPC_RESPONSE_SIZE));
    }

    @Override
    public void sendResponse(String message) {
        // restore Logging context on callback.
        try (Logging.MDCCloseable mdc = Logging.withContextMapCloseable(loggingContext)) {
            // When message is not null, it's called from kafka consumer otherwise it is from timeout tracker.
            if (message != null && expirationTime >= System.currentTimeMillis()) {
                T response = rpcModule.unmarshalResponse(message);
                if (response.getErrorMessage() != null) {
                    responseFuture.completeExceptionally(new RemoteExecutionException(response.getErrorMessage()));
                    span.log(response.getErrorMessage());
                    failedMeter.mark();
                } else {
                    responseFuture.complete(response);
                }
                isProcessed = true;
                responseSize.update(message.getBytes().length);
            } else {
                responseFuture.completeExceptionally(new RequestTimedOutException(new TimeoutException()));
                span.setTag(TAG_TIMEOUT, "true");
                failedMeter.mark();
            }
            rpcDuration.update(System.currentTimeMillis() - requestCreationTime);
            span.finish();
            clientFactory.getRpcResponseMap().remove(rpcId);
            clientFactory.getMessageCache().remove(rpcId);
        } catch (Exception e) {
            LOG.warn("error while handling response for RPC request id {}", rpcId, e);
        }
    }

    @Override
    public int compareTo(Delayed other) {
        long myDelay = getDelay(TimeUnit.MILLISECONDS);
        long otherDelay = other.getDelay(TimeUnit.MILLISECONDS);
        return Long.compare(myDelay, otherDelay);
    }

    @Override
    public long getDelay(TimeUnit unit) {
        long now = System.currentTimeMillis();
        return unit.convert(expirationTime - now, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isProcessed() {
        return isProcessed;
    }

    @Override
    public String getRpcId() {
        return rpcId;
    }

}
