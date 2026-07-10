package com.panopticon.registry;

import com.panopticon.model.DashboardDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DashboardRegistry {

    private final Map<String, DashboardDefinition> byId;

    public DashboardRegistry(List<DashboardDefinition> dashboards) {
        Map<String, DashboardDefinition> map = new LinkedHashMap<>();
        for (DashboardDefinition dashboard : dashboards) {
            map.put(dashboard.id(), dashboard);
        }
        // Map.copyOf() does not guarantee iteration order; wrap the LinkedHashMap instead
        // so monitor-mode rotation follows filename order.
        this.byId = Collections.unmodifiableMap(map);
    }

    public Optional<DashboardDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Insertion order (by filename) is preserved and used as monitor-mode rotation order. */
    public Collection<DashboardDefinition> all() {
        return byId.values();
    }
}
