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

package org.opennms.netmgt.dnsresolver.netty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.opennms.netmgt.dnsresolver.api.DnsResolver;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.xml.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Strings;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.netty.resolver.dns.DefaultDnsCache;
import io.netty.resolver.dns.DnsCache;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.internal.SocketUtils;

/**
 * Asynchronous DNS resolution using Netty.
 *
 * Creates multiple resolvers (aka contexts) against which the queries
 * are randomized in order to improve performance. By default we create 2 * num cores
 * contexts.
 *
 * Uses a circuit breaker in order to ensure that callers do not continue to be bogged down
 * if resolution fails.
 *
 * @author jwhite
 */
public class NettyDnsResolver implements DnsResolver {
    private static final Logger LOG = LoggerFactory.getLogger(NettyDnsResolver.class);

    public static final String CIRCUIT_BREAKER_STATE_CHANGE_EVENT_UEI = "uei.opennms.org/circuitBreaker/stateChange";

    private final EventForwarder eventForwarder;
    private final Timer lookupTimer;
    private final Meter lookupsSuccessful;
    private final Meter lookupsFailed;
    private final Meter lookupsRejectedByCircuitBreaker;

    private int numContexts = 0;
    private String nameservers = null;
    private long queryTimeoutMillis = TimeUnit.SECONDS.toMillis(5);

    private List<NettyResolverContext> contexts;
    private Iterator<NettyResolverContext> iterator;
    private DnsCache cache;

    private CircuitBreaker circuitBreaker;

    public NettyDnsResolver(EventForwarder eventForwarder, MetricRegistry metrics) {
        this.eventForwarder = Objects.requireNonNull(eventForwarder);
        lookupTimer = metrics.timer("lookups");
        lookupsSuccessful = metrics.meter("lookupsSuccessful");
        lookupsFailed = metrics.meter("lookupsFailed");
        lookupsRejectedByCircuitBreaker = metrics.meter("lookupsRejectedByCircuitBreaker");
    }

    public void init() {
        numContexts = Math.max(0, numContexts);
        if (numContexts == 0) {
            numContexts = Runtime.getRuntime().availableProcessors() * 2;
        }
        LOG.debug("Initializing Netty resolver with {} contexts and resolvers: {}", numContexts);

        contexts = new ArrayList<>(numContexts);
        cache = new DefaultDnsCache();
        for (int i = 0; i < numContexts; i++) {
            NettyResolverContext context = new NettyResolverContext(this, cache, i);
            context.init();
            contexts.add(context);
        }
        iterator = new RandomIterator<>(contexts).iterator();

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .ringBufferSizeInHalfOpenState(10)
                .ringBufferSizeInClosedState(100)
                .recordExceptions(DnsNameResolverTimeoutException.class)
                .build();
        circuitBreaker = CircuitBreaker.of("nettyDnsResolver", circuitBreakerConfig);

        circuitBreaker.getEventPublisher()
                .onStateTransition(e -> {
                    // Send an event when the circuit breaker's state changes
                    final Event event = new EventBuilder(CIRCUIT_BREAKER_STATE_CHANGE_EVENT_UEI, NettyDnsResolver.class.getCanonicalName())
                            .addParam("name", circuitBreaker.getName())
                            .addParam("fromState", e.getStateTransition().getFromState().toString())
                            .addParam("toState", e.getStateTransition().getToState().toString())
                            .getEvent();
                    eventForwarder.sendNow(event);
                })
                .onSuccess(e -> {
                    lookupsSuccessful.mark();
                })
                .onError(e -> {
                    lookupsFailed.mark();
                })
                .onCallNotPermitted(e -> {
                    lookupsRejectedByCircuitBreaker.mark();
                });
    }

    public void destroy() {
        for (NettyResolverContext context : contexts) {
            try {
                context.destroy();
            } catch (Exception e) {
                LOG.warn("Error occurred while destroying context.", e);
            }
        }
        contexts.clear();
    }

    @Override
    public CompletableFuture<Optional<InetAddress>> lookup(String hostname) {
        return circuitBreaker.executeCompletionStage(() -> {
            final NettyResolverContext resolverContext = iterator.next();
            final Timer.Context timerContext = lookupTimer.time();
            return resolverContext.lookup(hostname).whenComplete((res, ex) -> {
                timerContext.stop();
            });
        }).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Optional<String>> reverseLookup(InetAddress inetAddress) {
        return circuitBreaker.executeCompletionStage(() -> {
            final NettyResolverContext resolverContext = iterator.next();
            final Timer.Context timerContext = lookupTimer.time();
            return resolverContext.reverseLookup(inetAddress).whenComplete((res, ex) -> {
                timerContext.stop();
            });
        }).toCompletableFuture();
    }

    public int getNumContexts() {
        return numContexts;
    }

    public void setNumContexts(int numContexts) {
        this.numContexts = numContexts;
    }

    public String getNameservers() {
        return nameservers;
    }

    public void setNameservers(String nameservers) {
        this.nameservers = nameservers;
    }

    public long getQueryTimeoutMillis() {
        return queryTimeoutMillis;
    }

    public void setQueryTimeoutMillis(long queryTimeoutMillis) {
        this.queryTimeoutMillis = queryTimeoutMillis;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public DnsServerAddressStreamProvider getNameServerProvider() {
        if (Strings.isNullOrEmpty(nameservers)) {
            // Use the platform default
            return DnsServerAddressStreamProviders.platformDefault();
        }
        final String servers[] = nameservers.split(",");
        return new SequentialDnsServerAddressStreamProvider(Arrays.stream(servers)
                .map(s -> {
                    String parts[] = s.split(":");
                    if (parts.length > 1) {
                        return SocketUtils.socketAddress(parts[0], Integer.parseInt(parts[1]));
                    } else {
                        return SocketUtils.socketAddress(parts[0],53);
                    }
                })
                .toArray(InetSocketAddress[]::new));
    }
}
