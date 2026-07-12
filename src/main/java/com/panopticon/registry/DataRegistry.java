package com.panopticon.registry;

import com.panopticon.model.DataDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory lookup of data definitions by id. The backing map is an
 * immutable snapshot behind an {@link AtomicReference}: readers always see
 * one consistent, fully-validated config generation, and a hot reload
 * ({@code POST /api/config/reload}) swaps the whole snapshot in a single
 * atomic step — never a half-updated view.
 */
public class DataRegistry {

    private final AtomicReference<Map<String, DataDefinition>> byId = new AtomicReference<>();

    public DataRegistry(List<DataDefinition> definitions) {
        replace(definitions);
    }

    /** Swaps in a new config generation. Callers must have validated it first (see RegistryConfig/ConfigReloadService). */
    public void replace(List<DataDefinition> definitions) {
        Map<String, DataDefinition> map = new LinkedHashMap<>();
        for (DataDefinition definition : definitions) {
            map.put(definition.id(), definition);
        }
        byId.set(Map.copyOf(map));
    }

    public Optional<DataDefinition> find(String id) {
        return Optional.ofNullable(byId.get().get(id));
    }

    public Collection<DataDefinition> all() {
        return byId.get().values();
    }
}
