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

package org.opennms.netmgt.telemetry.protocols.sflow.parser.proto.flows;

import java.nio.ByteBuffer;
import java.util.Optional;

import org.bson.BsonWriter;
import org.opennms.netmgt.telemetry.common.utils.BufferUtils;
import org.opennms.netmgt.telemetry.protocols.sflow.parser.SampleDatagramEnrichment;
import org.opennms.netmgt.telemetry.protocols.sflow.parser.InvalidPacketException;
import org.opennms.netmgt.telemetry.protocols.sflow.parser.SampleDatagramVisitor;
import org.opennms.netmgt.telemetry.protocols.sflow.parser.proto.Array;

import com.google.common.base.MoreObjects;

// struct flow_sample_expanded {
//    unsigned int sequence_number;  /* Incremented with each flow sample
//                                      generated by this source_id.
//                                      Note: If the agent resets the
//                                            sample_pool then it must
//                                            also reset the sequence_number.*/
//    sflow_data_source_expanded source_id; /* sFlowDataSource */
//    unsigned int sampling_rate;    /* sFlowPacketSamplingRate */
//    unsigned int sample_pool;      /* Total number of packets that could have
//                                      been sampled (i.e. packets skipped by
//                                      sampling process + total number of
//                                      samples) */
//    unsigned int drops;            /* Number of times that the sFlow agent
//                                      detected that a packet marked to be
//                                      sampled was dropped due to
//                                      lack of resources. The drops counter
//                                      reports the total number of drops
//                                      detected since the agent was last reset.
//                                      A high drop rate indicates that the
//                                      management agent is unable to process
//                                      samples as fast as they are being
//                                      generated by hardware. Increasing
//                                      sampling_rate will reduce the drop
//                                      rate. Note: An agent that cannot
//                                      detect drops will always report
//                                      zero. */
// 
//    interface_expanded input;      /* Interface packet was received on. */
//    interface_expanded output;     /* Interface packet was sent on. */
// 
//    flow_record flow_records<>;    /* Information about a sampled packet */
// };

public class FlowSampleExpanded implements SampleData {
    public final long sequence_number;
    public final SFlowDataSourceExpanded source_id;
    public final long sampling_rate;
    public final long sample_pool;
    public final long drops;
    public final InterfaceExpanded input;
    public final InterfaceExpanded output;
    public final Array<FlowRecord> flow_records;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("sequence_number", this.sequence_number)
                .add("source_id", this.source_id)
                .add("sampling_rate", this.sampling_rate)
                .add("sample_pool", this.sample_pool)
                .add("drops", this.drops)
                .add("input", this.input)
                .add("output", this.output)
                .add("flow_records", this.flow_records)
                .toString();
    }

    public FlowSampleExpanded(final ByteBuffer buffer) throws InvalidPacketException {
        this.sequence_number = BufferUtils.uint32(buffer);
        this.source_id = new SFlowDataSourceExpanded(buffer);
        this.sampling_rate = BufferUtils.uint32(buffer);
        this.sample_pool = BufferUtils.uint32(buffer);
        this.drops = BufferUtils.uint32(buffer);
        this.input = new InterfaceExpanded(buffer);
        this.output = new InterfaceExpanded(buffer);
        this.flow_records = new Array(buffer, Optional.empty(), FlowRecord::new);
    }

    @Override
    public void writeBson(final BsonWriter bsonWriter, final SampleDatagramEnrichment svcs) {
        bsonWriter.writeStartDocument();
        bsonWriter.writeInt64("sequence_number", this.sequence_number);
        bsonWriter.writeName("source_id");
        this.source_id.writeBson(bsonWriter, svcs);
        bsonWriter.writeInt64("sampling_rate", this.sampling_rate);
        bsonWriter.writeInt64("sample_pool", this.sample_pool);
        bsonWriter.writeInt64("drops", this.drops);
        bsonWriter.writeName("input");
        this.input.writeBson(bsonWriter, svcs);
        bsonWriter.writeName("output");
        this.output.writeBson(bsonWriter, svcs);
        bsonWriter.writeStartDocument("flows");
        for (final FlowRecord flowRecord : this.flow_records) {
            bsonWriter.writeName(flowRecord.dataFormat.toId());
            flowRecord.writeBson(bsonWriter, svcs);
        }
        bsonWriter.writeEndDocument();
        bsonWriter.writeEndDocument();
    }

    @Override
    public void visit(final SampleDatagramVisitor visitor) {
        visitor.accept(this);
        for (final FlowRecord flowRecord : this.flow_records) {
            flowRecord.visit(visitor);
        }
    }
}
