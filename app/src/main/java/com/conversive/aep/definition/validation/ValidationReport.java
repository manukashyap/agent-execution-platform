package com.conversive.aep.definition.validation;

import java.util.List;

/** All findings of one validation run; the definition is accepted iff {@link #errors()} is empty. */
public record ValidationReport(List<Issue> errors, List<Issue> warnings) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    /** @param nodeId null for workflow-level findings */
    public record Issue(String code, String nodeId, String message) {
    }
}
