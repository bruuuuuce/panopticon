package com.panopticon.api;

import com.panopticon.api.dto.ApiError;
import com.panopticon.data.DataExecutionException;
import com.panopticon.data.UnknownDataException;
import com.panopticon.data.UnsupportedProviderException;
import com.panopticon.data.jdbc.SqlGuardException;
import com.panopticon.registry.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }

    @ExceptionHandler(UnknownDataException.class)
    public ResponseEntity<ApiError> handleUnknownData(UnknownDataException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }

    @ExceptionHandler(SqlGuardException.class)
    public ResponseEntity<ApiError> handleSqlGuard(SqlGuardException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("sql_rejected", e.getMessage()));
    }

    @ExceptionHandler(DataSourceRegistry.NoSuchDataSourceException.class)
    public ResponseEntity<ApiError> handleUnknownDatasource(DataSourceRegistry.NoSuchDataSourceException e) {
        // The exception message enumerates every configured datasource name -
        // useful in the log, not something to hand to an unauthenticated client.
        log.error("Datasource lookup failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("unknown_datasource", "The datasource referenced by this data definition is not configured"));
    }

    @ExceptionHandler(UnsupportedProviderException.class)
    public ResponseEntity<ApiError> handleUnsupportedProvider(UnsupportedProviderException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("unsupported_provider", e.getMessage()));
    }

    @ExceptionHandler(DataExecutionException.class)
    public ResponseEntity<ApiError> handleDataExecution(DataExecutionException e) {
        log.warn("Data execution failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of("data_execution_failed", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Raw messages from arbitrary exceptions can leak paths, SQL or driver
        // internals; the stack trace goes to the log, the client gets a constant.
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("internal_error", "Unexpected internal error"));
    }
}
