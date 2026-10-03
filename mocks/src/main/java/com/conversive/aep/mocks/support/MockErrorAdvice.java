package com.conversive.aep.mocks.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MockErrorAdvice {

    @ExceptionHandler(MockHttpException.class)
    public ResponseEntity<Map<String, Object>> handle(MockHttpException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatusCode.valueOf(e.status()));
        e.headers().forEach(builder::header);
        return builder.body(e.body());
    }

    @ExceptionHandler(DropConnectionException.class)
    public void drop(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(DropConnectionValve.DROP_ATTRIBUTE, Boolean.TRUE);
        response.reset();
    }
}
