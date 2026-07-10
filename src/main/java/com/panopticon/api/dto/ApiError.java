package com.panopticon.api.dto;

import java.time.Instant;

public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
