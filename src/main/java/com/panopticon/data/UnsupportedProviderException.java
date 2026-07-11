package com.panopticon.data;

import java.util.Set;

/** A data definition's {@code provider} value doesn't match any registered {@link DataProvider}. */
public class UnsupportedProviderException extends RuntimeException {
    public UnsupportedProviderException(String providerType, Set<String> known) {
        super("Unsupported provider type '%s'. Registered providers: %s".formatted(providerType, known));
    }
}
