package com.conversive.aep.api.auth;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.tenancy.ApiPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresScope}. A /v1 handler without the annotation is refused, so a new endpoint
 * cannot ship unprotected by accident.
 */
public class ScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresScope required = method.getMethodAnnotation(RequiresScope.class);
        if (required == null) {
            throw new NonRetryableError(ErrorCodes.FORBIDDEN, "endpoint has no scope policy");
        }
        Object principal = request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof ApiPrincipal p) || !p.has(required.value())) {
            throw new NonRetryableError(ErrorCodes.FORBIDDEN, "API key lacks scope " + required.value());
        }
        return true;
    }
}
