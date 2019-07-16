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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.opennms.core.utils.InetAddressUtils;
import org.osgi.framework.BundleContext;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;

public class DnsJavaResolverTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Spy
    private FakeBundleContext bundleContext;

    private DnsJavaResolver dnsResolver;

    @Before
    public void before() {
        createNewDnsResolver();
    }

    @Test
    public void setDnsServersTest() throws Exception {
        final List<String> addresses1 = getServers(dnsResolver.getResolver());
        assertThat(addresses1.size(), greaterThan(0));

        dnsResolver.setDnsServers("9.8.7.6", "8.7.6.5");
        final List<String> addresses2 = getServers(dnsResolver.getResolver());

        assertEquals(2, addresses2.size());
        assertThat(addresses2, hasItem("9.8.7.6"));
        assertThat(addresses2, hasItem("8.7.6.5"));

        dnsResolver.setDnsServers("4.3.2.1");
        final List<String> addresses3 = getServers(dnsResolver.getResolver());

        assertEquals(1, addresses3.size());
        assertThat(addresses3, hasItem("4.3.2.1"));

        dnsResolver.setDnsServers();

        final List<String> addresses4 = getServers(dnsResolver.getResolver());
        assertThat(addresses4.size(), greaterThan(0));
    }

    @Test
    public void enableDisableTest() throws ExecutionException, InterruptedException {
        final Optional<String> hostname1 = dnsResolver.reverseLookup(InetAddressUtils.addr("1.1.1.1")).get();
        assertThat(hostname1.isPresent(), equalTo(false));

        this.bundleContext.properties.put(DnsJavaResolver.DNS_ENABLE, "true");
        createNewDnsResolver();

        final Optional<String> hostname2 = dnsResolver.reverseLookup(InetAddressUtils.addr("1.1.1.1")).get();
        assertEquals("one.one.one.one", hostname2.get());

        this.bundleContext.properties.put(DnsJavaResolver.DNS_ENABLE, "false");
        createNewDnsResolver();

        final Optional<String> hostname3 = dnsResolver.reverseLookup(InetAddressUtils.addr("1.1.1.1")).get();
        assertThat(hostname3.isPresent(), equalTo(false));
    }

    @Test
    public void resolveTest() throws UnknownHostException, ExecutionException, InterruptedException {
        this.bundleContext.properties.put(DnsJavaResolver.DNS_ENABLE, "true");
        createNewDnsResolver();

        final Optional<String> hostname1 = dnsResolver.reverseLookup(InetAddress.getByAddress(new byte[]{1, 1, 1, 1})).get();
        assertEquals("one.one.one.one", hostname1.get());

        final Optional<String> hostname2 = dnsResolver.reverseLookup(InetAddressUtils.addr("1.1.1.1")).get();
        assertEquals("one.one.one.one", hostname2.get());

        final Optional<String> hostname3 = dnsResolver.reverseLookup(InetAddressUtils.addr("2606:4700:4700::1111")).get();
        assertEquals("one.one.one.one", hostname3.get());
    }

    @Test
    public void resolveFailTest() throws ExecutionException, InterruptedException {
        // 198.51.100.0/24 should be TEST-NET-2 (see RFC #5737). Should fail...
        final Optional<String> hostname1 = dnsResolver.reverseLookup(InetAddressUtils.addr("198.51.100.1")).get();
        assertEquals(Optional.empty(), hostname1);

        final Optional<String> hostname2 = dnsResolver.reverseLookup(InetAddressUtils.addr("fe80::")).get();
        assertEquals(Optional.empty(), hostname2);
    }

    @Test
    public void setSystemPropertiesTest() throws Exception {
        final List<String> addresses1 = getServers(dnsResolver.getResolver());
        assertThat(addresses1.size(), greaterThan(0));

        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_ENABLE, "true");
        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_PRIMARY_SERVER, "1.1.1.1");
        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_SECONDARY_SERVER, "8.8.8.8");
        createNewDnsResolver();
        dnsResolver.reverseLookup("1.1.1.1");

        final List<String> addresses2 = getServers(dnsResolver.getResolver());
        assertEquals(2, addresses2.size());
        assertThat(addresses2, hasItem("1.1.1.1"));
        assertThat(addresses2, hasItem("8.8.8.8"));

        this.bundleContext.properties.clear();
        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_ENABLE, "true");
        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_PRIMARY_SERVER, "8.8.4.4");
        createNewDnsResolver();
        dnsResolver.reverseLookup("1.1.1.1");

        final List<String> addresses3 = getServers(dnsResolver.getResolver());
        assertEquals(1, addresses3.size());
        assertThat(addresses3, hasItem("8.8.4.4"));

        this.bundleContext.properties.clear();
        this.bundleContext.properties.setProperty(DnsJavaResolver.DNS_ENABLE, "false");
        createNewDnsResolver();
        dnsResolver.reverseLookup("1.1.1.1");

        final List<String> addresses4 = getServers(dnsResolver.getResolver());
        assertThat(addresses4.size(), greaterThan(0));
    }

    private List<String> getServers(final ExtendedResolver extendedResolver) throws Exception {
        final List<String> list = new ArrayList<>();

        for (final Resolver resolver : extendedResolver.getResolvers()) {
            final SimpleResolver simpleResolver = (SimpleResolver) resolver;

            final Field privateAddressField = SimpleResolver.class.getDeclaredField("address");
            privateAddressField.setAccessible(true);
            list.add(((InetSocketAddress) privateAddressField.get(simpleResolver)).getAddress().getHostAddress());
        }
        return list;
    }

    private void createNewDnsResolver() {
        dnsResolver = new DnsJavaResolver(bundleContext) {
            @Override
            public CompletableFuture<Optional<String>> reverseLookup(final InetAddress inetAddress) {
                if (!isEnabled()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                // Don't issue actual DNS requests in this test
                return CompletableFuture.completedFuture(Optional.of("one.one.one.one"));
            }
        };
    }

    public abstract static class FakeBundleContext implements BundleContext {
        public final Properties properties = new Properties();

        @Override
        public String getProperty(final String key) {
            return (String) this.properties.get(key);
        }
    }
}
