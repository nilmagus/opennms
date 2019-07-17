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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.opennms.core.utils.InetAddressUtils;

import com.google.common.base.Stopwatch;

public class NettyDnsResolverTest {

    private NettyDnsResolver dnsResolver;

    @Before
    public void setUp() {
        dnsResolver = new NettyDnsResolver();
        dnsResolver.init();
    }

    @After
    public void destroy() {
        dnsResolver.destroy();
    }

    @Test
    public void canDoReverseLookups() throws UnknownHostException, ExecutionException, InterruptedException {
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("1.1.1.1")).get().get(), equalTo("one.one.one.one"));
        assertThat(dnsResolver.reverseLookup(InetAddress.getByName("173.242.186.51")).get().get(), equalTo("rnd.opennms.ca"));
        // TESTNET
        assertThat(dnsResolver.reverseLookup(InetAddressUtils.addr("fe80::")).get().isPresent(), equalTo(false));
    }

    @Ignore
    @Test
    public void canPerformManyLookupsQuickly() throws UnknownHostException, ExecutionException, InterruptedException {
        final int numLookups = 254;
        Stopwatch stopwatch = Stopwatch.createStarted();

        final List<CompletableFuture<Optional<String>>> futures = new ArrayList<>();
        final Set<Optional<String>> results = new LinkedHashSet<>();
        System.out.printf("Issuing %d reverse lookups asynchronously.\n", numLookups);
        for (InetAddress addr = InetAddressUtils.addr("10.0.0.1");
             !addr.equals(InetAddressUtils.addr("10.0.1.255"));
             addr = InetAddressUtils.addr(InetAddressUtils.incr(InetAddressUtils.str(addr)))) {
            futures.add(dnsResolver.reverseLookup(addr));
            dnsResolver.reverseLookup(addr).whenComplete((hostname,ex) -> {
                results.add(hostname);
            });
        }

        // Wait
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})).get();
        stopwatch.stop();
        System.out.printf("Processed %d requests in %dms.\n", futures.size(), stopwatch.elapsed(MILLISECONDS));

        // Validate
        assertThat(futures, hasSize(numLookups));
        assertThat(results, hasSize(1));
        assertThat(results, contains(Optional.<String>empty()));
    }
}
