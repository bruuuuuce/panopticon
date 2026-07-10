package com.panopticon.api;

import com.panopticon.api.dto.ApiError;
import com.panopticon.query.QueryExecutionException;
import com.panopticon.query.SqlGuardException;
import com.panopticon.query.UnknownQueryException;
import com.panopticon.registry.DatasourceRegistry;
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

    @ExceptionHandler(UnknownQueryException.class)
    public ResponseEntity<ApiError> handleUnknownQuery(UnknownQueryException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }

    @ExceptionHandler(SqlGuardException.class)
    public ResponseEntity<ApiError> handleSqlGuard(SqlGuardException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("sql_rejected", e.getMessage()));
    }

    @ExceptionHandler(DatasourceRegistry.NoSuchDatasourceException.class)
    public ResponseEntity<ApiError> handleUnknownDatasource(DatasourceRegistry.NoSuchDatasourceException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("unknown_datasource", e.getMessage()));
    }

    @ExceptionHandler(QueryExecutionException.class)
    public ResponseEntity<ApiError> handleQueryExecution(QueryExecutionException e) {
        log.warn("Query execution failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of("query_execution_failed", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("internal_error", e.getMessage()));
    }
}
