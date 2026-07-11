package com.panopticon.loader;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors, int dashboardCount, int dataCount) {

    public static ValidationResult ok(int dashboardCount, int dataCount) {
        return new ValidationResult(true, List.of(), dashboardCount, dataCount);
    }

    public static ValidationResult failed(List<ValidationError> errors, int dashboardCount, int dataCount) {
        return new ValidationResult(errors.isEmpty(), errors, dashboardCount, dataCount);
    }
}
