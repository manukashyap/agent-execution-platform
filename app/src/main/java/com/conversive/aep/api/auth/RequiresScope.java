package com.conversive.aep.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The API-key scope a handler needs; {@code *} on the key grants all. Missing scope → 403 FORBIDDEN. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresScope {

    String WORKFLOWS_READ = "workflows:read";
    String WORKFLOWS_WRITE = "workflows:write";
    String EXECUTIONS_READ = "executions:read";
    String EXECUTIONS_WRITE = "executions:write";

    String value();
}
