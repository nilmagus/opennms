/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019-2019 The OpenNMS Group, Inc.
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

package org.opennms.netmgt.spotlight.providers.action;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.opennms.netmgt.provision.persist.requisition.Requisition;
import org.opennms.netmgt.spotlight.api.Contexts;
import org.opennms.netmgt.spotlight.api.SearchProvider;
import org.opennms.netmgt.spotlight.api.SearchQuery;
import org.opennms.netmgt.spotlight.api.SearchResult;
import org.opennms.netmgt.spotlight.api.SearchResultItem;
import org.opennms.netmgt.spotlight.providers.QueryUtils;
import org.opennms.web.svclayer.api.RequisitionAccessService;

import com.google.common.collect.Lists;

public class RequisitionSearchProvider implements SearchProvider {

    private final RequisitionAccessService requisitionAccessService;

    public RequisitionSearchProvider(final RequisitionAccessService requisitionAccessService) {
        this.requisitionAccessService = Objects.requireNonNull(requisitionAccessService);
    }

    @Override
    public boolean contributesTo(String contextName) {
        return Contexts.Action.getName().equals(contextName);
    }

    @Override
    public SearchResult query(SearchQuery query) {
        final List<Requisition> requisitions = Lists.newArrayList(requisitionAccessService.getRequisitions())
                .stream().filter(r -> QueryUtils.matches(r.getForeignSource(), query.getInput()))
                .collect(Collectors.toList());
        final List<SearchResultItem> resultItems = requisitions.stream().map(r -> {
                final SearchResultItem searchResultItem = new SearchResultItem();
                searchResultItem.setContext(Contexts.Action);
                searchResultItem.setIdentifier(r.getForeignSource());
                searchResultItem.setUrl(String.format("admin/ng-requisitions/index.jsp#/requisitions/%s",r.getForeignSource()));
                searchResultItem.setLabel(String.format("Edit Requisition '%s'", r.getForeignSource()));
                searchResultItem.setIcon("fa fa-pencil");
                return searchResultItem;
            })
            .collect(Collectors.toList());
        final SearchResult searchResult = new SearchResult(Contexts.Action).withTotalCount(requisitions.size()).withResults(resultItems);
        return searchResult;
    }
}