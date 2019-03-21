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

package org.opennms.smoketest.minion;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Date;

import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.api.EventDao;
import org.opennms.netmgt.dao.hibernate.EventDaoHibernate;
import org.opennms.netmgt.model.OnmsEvent;
import org.opennms.smoketest.NullTestEnvironment;
import org.opennms.smoketest.OpenNMSSeleniumTestCase;
import org.opennms.smoketest.utils.DaoUtils;
import org.opennms.smoketest.utils.HibernateDaoFactory;
import org.opennms.test.system.api.NewTestEnvironment.ContainerAlias;
import org.opennms.test.system.api.TestEnvironment;
import org.opennms.test.system.api.TestEnvironmentBuilder;
import org.opennms.test.system.api.utils.SshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventSinkIT {

    private static final Logger LOG = LoggerFactory.getLogger(EventSinkIT.class);
    private static final String SENDER_IP = "192.168.1.1";
    protected static TestEnvironment m_testEnvironment;

    @ClassRule
    public static final TestEnvironment getTestEnvironment() {
        if (!OpenNMSSeleniumTestCase.isDockerEnabled()) {
            return new NullTestEnvironment();
        }
        try {
            final TestEnvironmentBuilder builder = TestEnvironment.builder().opennms().minion().kafka();
            builder.withOpenNMSEnvironment()
                    // Switch sink to Kafka using opennms-properties.d file
                    .addFile(MinionHeartbeatOutageKafkaIT.class.getResource("/opennms.properties.d/kafka-sink.properties"), "etc/opennms.properties.d/kafka-sink.properties");
            builder.withMinionEnvironment()
                    // Switch sink to Kafka using features.boot file
                    .addFile(MinionHeartbeatOutageKafkaIT.class.getResource("/featuresBoot.d/kafka.boot"), "etc/featuresBoot.d/kafka.boot");
            OpenNMSSeleniumTestCase.configureTestEnvironment(builder);
            m_testEnvironment = builder.build();
            return m_testEnvironment;
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Before
    public void checkForDockerAndLoadExecutor() {
        Assume.assumeTrue(OpenNMSSeleniumTestCase.isDockerEnabled());
    }

    @Test
    public void canReceiveEvents() throws UnknownHostException {
        Date startOfTest = new Date();
        InetSocketAddress sshAddr = m_testEnvironment.getServiceAddress(ContainerAlias.MINION, 8201);
        assertTrue("failed to send event from Minion", sendEventsFromMinion(sshAddr));
        InetSocketAddress pgsql = m_testEnvironment.getServiceAddress(ContainerAlias.POSTGRES, 5432);
        HibernateDaoFactory daoFactory = new HibernateDaoFactory(pgsql);
        EventDao eventDao = daoFactory.getDao(EventDaoHibernate.class);
        final OnmsEvent onmsEvent = await().atMost(2, MINUTES).pollInterval(10, SECONDS)
                .until(DaoUtils.findMatchingCallable(eventDao, new CriteriaBuilder(OnmsEvent.class).eq("eventUei", "uei.opennms.org/alarms/trigger")
                        .eq("eventSource", "karaf-shell").ge("eventCreateTime", startOfTest).toCriteria()), notNullValue());

        assertNotNull("The event sent is not received at OpenNMS", onmsEvent);

    }


    private boolean sendEventsFromMinion(InetSocketAddress sshAddr) {
        try (final SshClient sshClient = new SshClient(sshAddr, "admin", "admin")) {
            // Issue events:send command
            PrintStream pipe = sshClient.openShell();
            pipe.println("events:send -u 'uei.opennms.org/alarms/trigger'");
            pipe.println("logout");

            await().atMost(1, MINUTES).until(sshClient.isShellClosedCallable());
            // Grab the output
            String shellOutput = sshClient.getStdout();
            LOG.info("events:send output: {}", shellOutput);
            // Verify
            return shellOutput.contains("sent");
        } catch (Exception e) {
            LOG.error("Failed to send event from Minion", e);
        }
        return false;
    }

}