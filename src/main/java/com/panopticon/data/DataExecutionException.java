package com.panopticon.data;

/** A {@link DataProvider} execution failed (datasource error, timeout, upstream API failure, ...). */
public class DataExecutionException extends RuntimeException {
    public DataExecutionException(String message) {
        super(message);
    }

    public DataExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
