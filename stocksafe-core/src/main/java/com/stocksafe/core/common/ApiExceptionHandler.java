package com.stocksafe.core.common;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns errors into RFC 7807 JSON. Validation errors (400) come from the parent class. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handle(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }
}
