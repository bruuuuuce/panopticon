package com.panopticon.registry;

import com.panopticon.model.QueryDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class QueryRegistry {

    private final Map<String, QueryDefinition> byId;

    public QueryRegistry(List<QueryDefinition> queries) {
        Map<String, QueryDefinition> map = new LinkedHashMap<>();
        for (QueryDefinition query : queries) {
            map.put(query.id(), query);
        }
        this.byId = Map.copyOf(map);
    }

    public Optional<QueryDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<QueryDefinition> all() {
        return byId.values();
    }
}
