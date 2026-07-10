package com.panopticon.loader;

/**
 * Raised when dashboard/query JSON under {@code config/} cannot be read or
 * parsed. Kept unchecked since it is only ever thrown during startup
 * (where it should abort the app) or caught and converted into a
 * validation report by the config-validate endpoint.
 */
public class ConfigLoadException extends RuntimeException {
    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
