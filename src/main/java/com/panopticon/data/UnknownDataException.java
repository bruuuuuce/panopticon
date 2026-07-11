package com.panopticon.data;

/** A panel's {@code dataRef} (or a direct data id lookup) doesn't match any loaded data definition. */
public class UnknownDataException extends RuntimeException {
    public UnknownDataException(String dataId) {
        super("Unknown data id '%s'".formatted(dataId));
    }
}
