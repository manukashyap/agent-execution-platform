package com.conversive.aep.api;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import com.conversive.aep.definition.DefinitionRejectedException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps the error taxonomy onto HTTP status + envelope. Internals never reach the client. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCodes.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCodes.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCodes.FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCodes.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCodes.CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.VERSION_EXISTS, HttpStatus.CONFLICT),
            Map.entry(ErrorCodes.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCodes.CONCURRENCY_LIMIT, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCodes.START_FAILED, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCodes.UPSTREAM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCodes.NOT_IMPLEMENTED, HttpStatus.NOT_IMPLEMENTED));

    private final ApiProperties props;

    public ApiExceptionHandler(ApiProperties props) {
        this.props = props;
    }

    @ExceptionHandler(DefinitionRejectedException.class)
    ResponseEntity<ApiEnvelope<Void>> rejected(DefinitionRejectedException e) {
        return ResponseEntity.badRequest().body(ApiEnvelope.error(e.code(), e.getMessage(), e.issues()));
    }

    @ExceptionHandler(NonRetryableError.class)
    ResponseEntity<ApiEnvelope<Void>> nonRetryable(NonRetryableError e) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.status(status).body(ApiEnvelope.error(e.code(), e.getMessage()));
    }

    @ExceptionHandler(RetryableError.class)
    ResponseEntity<ApiEnvelope<Void>> retryable(RetryableError e) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.SERVICE_UNAVAILABLE);
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(e.nextRetryDelay())))
                .body(ApiEnvelope.error(e.code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiEnvelope<Void>> badRequest(Exception e) {
        log.debug("bad request: {}", e.toString());
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(ErrorCodes.VALIDATION_FAILED, "malformed request: " + safeMessage(e)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiEnvelope<Void>> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiEnvelope.error(ErrorCodes.NOT_FOUND, "no such route"));
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiEnvelope<Void>> unsupported(Exception e) {
        return ResponseEntity.badRequest().body(ApiEnvelope.error(ErrorCodes.VALIDATION_FAILED, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> unexpected(Exception e) {
        log.error("unhandled API error", e);
        return ResponseEntity.internalServerError().body(ApiEnvelope.error(ErrorCodes.INTERNAL, "internal error"));
    }

    private long retryAfterSeconds(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return props.startFailedRetryAfterS();
        }
        return Math.max(1, (delay.toMillis() + 999) / 1000);
    }

    private static String safeMessage(Exception e) {
        if (e instanceof HttpMessageNotReadableException) {
            return "request body is not valid JSON for this endpoint";
        }
        return e instanceof MethodArgumentTypeMismatchException m ? "invalid value for " + m.getName() : "bad request";
    }
}
