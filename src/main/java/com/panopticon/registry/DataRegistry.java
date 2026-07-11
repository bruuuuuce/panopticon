package com.panopticon.registry;

import com.panopticon.model.DataDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DataRegistry {

    private final Map<String, DataDefinition> byId;

    public DataRegistry(List<DataDefinition> definitions) {
        Map<String, DataDefinition> map = new LinkedHashMap<>();
        for (DataDefinition definition : definitions) {
            map.put(definition.id(), definition);
        }
        this.byId = Map.copyOf(map);
    }

    public Optional<DataDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<DataDefinition> all() {
        return byId.values();
    }
}
