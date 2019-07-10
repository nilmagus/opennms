/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2010-2019 The OpenNMS Group, Inc.
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

package org.opennms.netmgt.collection.support.builder;

import java.util.Objects;

import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.opennms.netmgt.collection.adapters.NodeLevelResourceAdapter;
import org.opennms.netmgt.collection.api.CollectionResource;
import org.opennms.netmgt.model.ResourcePath;

@XmlJavaTypeAdapter(NodeLevelResourceAdapter.class)
public class NodeLevelResource extends AbstractResource {

    private final int m_nodeId;
    private final String m_path;

    public NodeLevelResource(int nodeId) {
        this(nodeId, null);
    }

    /**
     * Allows node level resources to live in a sub-directory
     * of the node directory when the path is set to a non-null
     * value.
     *
     * This is generally not recommend, and these cases should be
     * modeled using {@link GenericTypeResource}s instead, however
     * it exists for backwards compatibility with the JMX collector.
     *
     * @deprecated use a {@link GenericTypeResource} instead
     * @param nodeId the node id
     * @param path sub-directory of the node directory
     */
    public NodeLevelResource(int nodeId, String path) {
        m_nodeId = nodeId;
        m_path = path;
    }

    public int getNodeId() {
        return m_nodeId;
    }

    public String getPath() {
        return m_path;
    }

    @Override
    public Resource getParent() {
        return null;
    }

    @Override
    public String getInstance() {
        // The instance is ambiguous for the node resource,
        // but the existing value used by the collection sets
        // generated by the SnmpCollector is 'node'
        return CollectionResource.RESOURCE_TYPE_NODE;
    }

    @Override
    public String getUnmodifiedInstance() {
        // The instance is ambiguous for the node resource,
        // but the existing value used by the collection sets
        // generated by the SnmpCollector is 'node'
        return CollectionResource.RESOURCE_TYPE_NODE;
    }

    @Override
    public ResourcePath getPath(CollectionResource resource) {
        return m_path == null ? ResourcePath.get() : ResourcePath.get(m_path);
    }

    @Override
    public String getLabel(CollectionResource resource) {
        // The existing value used by the collection sets
        // generated by the SnmpCollector is null
        return null;
    }

    @Override
    public String getTypeName() {
        return CollectionResource.RESOURCE_TYPE_NODE;
    }

    @Override
    public String toString() {
        return String.format("NodeLevelResource[nodeId=%d, path=%s]", m_nodeId, m_path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(m_nodeId, m_path, getTimestamp());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj == null) {
            return false;
        } else if (!(obj instanceof NodeLevelResource)) {
            return false;
        }
        NodeLevelResource other = (NodeLevelResource) obj;
        return Objects.equals(this.m_nodeId, other.m_nodeId)
                && Objects.equals(this.getPath(), other.getPath())
                && Objects.equals(this.getTimestamp(), other.getTimestamp());
    }

}
