package com.conversive.aep.definition;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.definition.validation.ValidationReport.Issue;
import java.util.List;

/** {@code VALIDATION_FAILED} carrying every validator error, so a client fixes them in one round trip. */
public class DefinitionRejectedException extends NonRetryableError {

    private final transient List<Issue> issues;

    public DefinitionRejectedException(String message, List<Issue> issues) {
        super(ErrorCodes.VALIDATION_FAILED, message);
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }
}
