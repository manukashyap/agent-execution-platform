package com.conversive.aep.api.auth;

import com.conversive.aep.api.ApiEnvelope;
import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.Hashing;
import com.conversive.aep.common.TenantContext;
import com.conversive.aep.tenancy.ApiPrincipal;
import com.conversive.aep.tenancy.persistence.ApiKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>} → SHA-256 → active {@code api_key}
 * row → {@link TenantContext} for the request. Anything else is 401 with the standard envelope.
 * The plaintext key is never logged or stored.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = ApiPrincipal.class.getName();
    static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER = "Bearer ";
    private static final int MAX_KEY_LENGTH = 256;

    private final ApiKeyRepository keys;
    private final ObjectMapper mapper;

    public ApiKeyAuthFilter(ApiKeyRepository keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiPrincipal> principal = presentedKey(request).flatMap(k -> keys.findActive(Hashing.sha256Hex(k)));
        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        TenantContext.set(principal.get().tenantId(), null);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    static Optional<String> presentedKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String key = null;
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            key = authorization.substring(BEARER.length()).trim();
        } else if (request.getHeader(API_KEY_HEADER) != null) {
            key = request.getHeader(API_KEY_HEADER).trim();
        }
        return key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH ? Optional.empty() : Optional.of(key);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        mapper.writeValue(response.getOutputStream(),
                ApiEnvelope.error(ErrorCodes.UNAUTHORIZED, "missing or invalid API key"));
    }
}
