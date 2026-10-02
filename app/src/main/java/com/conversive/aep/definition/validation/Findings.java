package com.conversive.aep.definition.validation;

import com.conversive.aep.definition.validation.ValidationReport.Issue;
import java.util.ArrayList;
import java.util.List;

/** Accumulator local to one validation run (never shared). */
final class Findings {

    private final List<Issue> errors = new ArrayList<>();
    private final List<Issue> warnings = new ArrayList<>();

    void error(String code, String nodeId, String message) {
        errors.add(new Issue(code, nodeId, message));
    }

    void warning(String code, String nodeId, String message) {
        warnings.add(new Issue(code, nodeId, message));
    }

    ValidationReport report() {
        return new ValidationReport(errors, warnings);
    }
}
