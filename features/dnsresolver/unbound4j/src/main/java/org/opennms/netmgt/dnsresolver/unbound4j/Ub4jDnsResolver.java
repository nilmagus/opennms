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

package org.opennms.netmgt.dnsresolver.unbound4j;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opennms.netmgt.dnsresolver.api.DnsResolver;
import org.opennms.unbound4j.api.Unbound4j;
import org.opennms.unbound4j.api.Unbound4jConfig;
import org.opennms.unbound4j.api.Unbound4jContext;
import org.opennms.unbound4j.impl.Unbound4jImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class Ub4jDnsResolver implements DnsResolver {
    private static final Logger LOG = LoggerFactory.getLogger(Ub4jDnsResolver.class);

    private final Cache<InetAddress, Optional<String>> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100000)
            .build();

    private final Unbound4j ub4j = new Unbound4jImpl();
    private Unbound4jContext ctx;

    private boolean useSystemResolver = true;
    private String unboundConfig;
    private int requestTimeoutSeconds;

    public void init() {
        final Unbound4jConfig.Builder builder = Unbound4jConfig.newBuilder()
                .useSystemResolver(useSystemResolver);
        if (!Strings.isNullOrEmpty(unboundConfig)) {
            builder.withUnboundConfig(unboundConfig);
        }
        if (requestTimeoutSeconds > 0) {
            builder.withRequestTimeout(requestTimeoutSeconds, TimeUnit.SECONDS);
        }
        final Unbound4jConfig config = builder.build();
        ctx = ub4j.newContext(config);
    }

    public void destroy() {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception e) {
                LOG.info("Error while closing unbound4j context.", e);
            }
            ctx = null;
        }
    }

    @Override
    public CompletableFuture<Optional<String>> reverseLookup(InetAddress addr) {
        Optional<String> hostname = cache.getIfPresent(addr);
        if (hostname != null) {
            return CompletableFuture.completedFuture(hostname);
        }
        return ub4j.reverseLookup(ctx, addr).whenComplete((hostnameFromDns, ex) -> {
           if (ex == null) {
               // Store the result in the cache
               cache.put(addr, hostnameFromDns);
           }
        });
    }

    public boolean isUseSystemResolver() {
        return useSystemResolver;
    }

    public void setUseSystemResolver(boolean useSystemResolver) {
        this.useSystemResolver = useSystemResolver;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public String getUnboundConfig() {
        return unboundConfig;
    }

    public void setUnboundConfig(String unboundConfig) {
        this.unboundConfig = unboundConfig;
    }
}
