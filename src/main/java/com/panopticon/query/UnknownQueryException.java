package com.panopticon.query;

/** A panel's {@code queryRef} (or a direct query id lookup) doesn't match any loaded query. */
public class UnknownQueryException extends RuntimeException {
    public UnknownQueryException(String queryId) {
        super("Unknown query id '%s'".formatted(queryId));
    }
}
