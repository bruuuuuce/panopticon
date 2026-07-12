package com.panopticon.registry;

import com.panopticon.model.DashboardDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory lookup of dashboards by id. Same atomic-snapshot design as
 * {@link DataRegistry}: an immutable generation behind an
 * {@link AtomicReference}, swapped whole by hot reload.
 */
public class DashboardRegistry {

    private final AtomicReference<Map<String, DashboardDefinition>> byId = new AtomicReference<>();

    public DashboardRegistry(List<DashboardDefinition> dashboards) {
        replace(dashboards);
    }

    /** Swaps in a new config generation. Callers must have validated it first (see RegistryConfig/ConfigReloadService). */
    public void replace(List<DashboardDefinition> dashboards) {
        Map<String, DashboardDefinition> map = new LinkedHashMap<>();
        for (DashboardDefinition dashboard : dashboards) {
            map.put(dashboard.id(), dashboard);
        }
        // Map.copyOf() does not guarantee iteration order; wrap the LinkedHashMap instead
        // so monitor-mode rotation follows filename order.
        byId.set(Collections.unmodifiableMap(map));
    }

    public Optional<DashboardDefinition> find(String id) {
        return Optional.ofNullable(byId.get().get(id));
    }

    /** Insertion order (by filename) is preserved and used as monitor-mode rotation order. */
    public Collection<DashboardDefinition> all() {
        return byId.get().values();
    }
}
