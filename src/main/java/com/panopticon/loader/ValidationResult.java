package com.panopticon.loader;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors, int dashboardCount, int queryCount) {

    public static ValidationResult ok(int dashboardCount, int queryCount) {
        return new ValidationResult(true, List.of(), dashboardCount, queryCount);
    }

    public static ValidationResult failed(List<ValidationError> errors, int dashboardCount, int queryCount) {
        return new ValidationResult(errors.isEmpty(), errors, dashboardCount, queryCount);
    }
}
