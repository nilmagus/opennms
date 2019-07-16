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

package org.opennms.netmgt.dnsresolver.dnsjava;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.dnsresolver.api.DnsResolver;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Cache;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.ReverseMap;
import org.xbill.DNS.Type;

import com.google.common.base.Strings;

public class DnsJavaResolver implements DnsResolver {
    private static final Logger LOG = LoggerFactory.getLogger(DnsJavaResolver.class);
    static final String DNS_PRIMARY_SERVER = "org.opennms.features.telemetry.dns.primaryServer";
    static final String DNS_SECONDARY_SERVER = "org.opennms.features.telemetry.dns.secondaryServer";
    static final String DNS_ENABLE = "org.opennms.features.telemetry.dns.enable";
    static final String DNS_CACHE_COUNT = "org.opennms.features.telemetry.dns.cache.count";
    static final String DNS_CACHE_MAX_TTL = "org.opennms.features.telemetry.dns.cache.maxttl";
    static final int DNS_CACHE_COUNT_DEFAULT = 50000;

    private ExtendedResolver resolver;
    private Cache cache = new Cache();

    private String primaryServer = null, secondaryServer = null;
    private boolean enable = false;
    private int cacheCount = 50000;
    private int cacheMaxTTL = -1;

    private final BundleContext bundleContext;

    public DnsJavaResolver(BundleContext bundleContext) {
        this.bundleContext = Objects.requireNonNull(bundleContext);

        try {
            resolver = new ExtendedResolver();
        } catch (UnknownHostException e) {
            LOG.debug("Cannot create resolver: {}", e.getMessage());
        }

        loadConfiguration();
    }

    private void loadConfiguration() {
        if (bundleContext == null) {
            return;
        }

        final String primaryServer = bundleContext.getProperty(DNS_PRIMARY_SERVER);
        final String secondaryServer = bundleContext.getProperty(DNS_SECONDARY_SERVER);
        final boolean enable = Boolean.parseBoolean(bundleContext.getProperty(DNS_ENABLE));

        if (enable != this.enable || !Objects.equals(primaryServer, this.primaryServer) || !Objects.equals(secondaryServer, this.secondaryServer)) {
            this.enable = enable;
            this.primaryServer = primaryServer;
            this.secondaryServer = secondaryServer;
            setDnsServers(primaryServer, secondaryServer);
        }

        final int cacheCount = Optional.ofNullable(bundleContext.getProperty(DNS_CACHE_COUNT)).map(Integer::parseInt).orElse(DNS_CACHE_COUNT_DEFAULT);
        final int cacheMaxTTL = Optional.ofNullable(bundleContext.getProperty(DNS_CACHE_MAX_TTL)).map(Integer::parseInt).orElse(-1);

        if (cacheCount != this.cacheCount || cacheMaxTTL != this.cacheMaxTTL) {
            this.cacheCount = cacheCount;
            this.cacheMaxTTL = cacheMaxTTL;

            this.cache = new Cache();
            this.cache.setMaxEntries(cacheCount);
            this.cache.setMaxCache(cacheMaxTTL);
            this.cache.setMaxNCache(cacheMaxTTL);
        }
    }

    public synchronized void setDnsServers(String... dnsServers) {
        final String[] notNullDnsServers = (dnsServers == null ? new String[]{} : Arrays.stream(dnsServers)
                .filter(e -> !Strings.isNullOrEmpty(e))
                .toArray(String[]::new)
        );

        try {
            if (notNullDnsServers.length == 0) {
                resolver = new ExtendedResolver();
            } else {
                resolver = new ExtendedResolver(notNullDnsServers);
            }
        } catch (UnknownHostException e) {
            LOG.debug("Cannot create resolver for given servers {}: {}", dnsServers, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enable;
    }

    ExtendedResolver getResolver() {
        return resolver;
    }

    public CompletableFuture<Optional<String>> reverseLookup(final String inetAddress) {
        return reverseLookup(InetAddressUtils.addr(inetAddress));
    }

    @Override
    public CompletableFuture<Optional<String>> reverseLookup(final InetAddress inetAddress) {
        if (!enable) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final Lookup lookup = new Lookup(ReverseMap.fromAddress(inetAddress), Type.PTR);
        lookup.setResolver(resolver);
        lookup.setCache(cache);

        final Record records[] = lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL) {
            final Optional<String> result = Arrays.stream(records)
                    .filter(PTRRecord.class::isInstance)
                    .reduce((first, other) -> {
                        LOG.warn("Reverse lookup of hostname got multiple results: {}", inetAddress);
                        return first;
                    })
                    .map(rr -> ((PTRRecord) rr).getTarget().toString())
                    // Strip of the trailing dot
                    .map(hostname -> hostname.substring(0, hostname.length() - 1));
            return CompletableFuture.completedFuture(result);
        } else {
            LOG.warn("Reverse lookup of hostname failed: {}", inetAddress);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
