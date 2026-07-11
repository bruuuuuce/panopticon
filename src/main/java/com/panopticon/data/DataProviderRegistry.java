package com.panopticon.data;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collects every {@link DataProvider} Spring bean and resolves one by its
 * {@code providerType()}. This is the whole "plugin registry" for now — new
 * data source types are added by writing a {@code @Component} that
 * implements {@link DataProvider}; Spring hands it to this registry
 * automatically via the {@code List<DataProvider>} constructor injection
 * below, no manual wiring required.
 */
@Component
public class DataProviderRegistry {

    private final Map<String, DataProvider> byType;

    public DataProviderRegistry(List<DataProvider> providers) {
        this.byType = providers.stream()
                .collect(Collectors.toUnmodifiableMap(DataProvider::providerType, Function.identity()));
    }

    public DataProvider resolve(String providerType) {
        DataProvider provider = byType.get(providerType);
        if (provider == null) {
            throw new UnsupportedProviderException(providerType, byType.keySet());
        }
        return provider;
    }

    public boolean supports(String providerType) {
        return byType.containsKey(providerType);
    }

    public Set<String> knownProviderTypes() {
        return byType.keySet();
    }
}
