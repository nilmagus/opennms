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

package org.opennms.web.tags;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import javax.servlet.jsp.JspContext;
import javax.servlet.jsp.JspWriter;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opennms.core.time.CentralizedDateTimeFormat;

public class DateTimeTagTest {

    @Test
    public void shouldOutputeDateTimeIncludingTimeZone() throws IOException {
        test("yyyy-MM-dd'T'HH:mm:ssxxx", Instant.now());
    }

    @Test
    public void shouldBeResilientAgainstNull() throws IOException {
        // we expect an empty String, same as fmt:formatDate outputs
        assertEquals("", new DateTimeTagInvoker().setInstant(null).invokeAndGet());
    }

    @Test
    public void shouldHonorSystemSettings() throws IOException {
        String format = "yyy-MM-dd";
        System.setProperty(CentralizedDateTimeFormat.SYSTEM_PROPERTY_DATE_FORMAT, format);
        test(format, Instant.now());
        System.clearProperty(CentralizedDateTimeFormat.SYSTEM_PROPERTY_DATE_FORMAT);
    }

    public void test(String expectedPattern, Instant time) throws IOException {
        String output = new DateTimeTagInvoker()
                .setInstant(time)
                .invokeAndGet();
        DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern(expectedPattern)
                .withZone(ZoneId.systemDefault());
        assertEquals(formatter.format(time), output);
    }

    // Helper class to be able to test easier
    private static class DateTimeTagInvoker {

        private DateTimeTag tag;
        private JspWriter jspWriter;

        private DateTimeTagInvoker() throws IOException {
            jspWriter = Mockito.mock(JspWriter.class);
            JspContext jspContext = Mockito.mock(JspContext.class);
            when(jspContext.getOut()).thenReturn(jspWriter);
            tag = new DateTimeTag(){
                @Override
                protected JspContext getJspContext() {
                    return jspContext;
                }
            };
        }

        DateTimeTagInvoker setDate(Date date){
            this.tag.setDate(date);
            return this;
        }

        DateTimeTagInvoker setInstant(Instant instant){
            this.tag.setInstant(instant);
            return this;
        }

        String invokeAndGet() throws IOException {
            this.tag.doTag();
            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            verify(jspWriter).write(output.capture());
            return output.getValue();
        }
    }
}