package com.aepsy.voucher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> business(ApiException failure) {
        return ResponseEntity.status(failure.status()).body(error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception failure) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Check the request fields and their formats."));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException failure) {
        var error = error("SERVICE_UNAVAILABLE", "Please retry using the same user and campaign.");
        LOG.warn("Database request failed traceId={} exception={}", error.get("traceId"), failure.getClass().getSimpleName());
        return ResponseEntity.status(503).body(error);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception failure) {
        var error = error("INTERNAL_ERROR", "The request could not be completed.");
        LOG.error("Unexpected failure traceId={}", error.get("traceId"), failure);
        return ResponseEntity.internalServerError().body(error);
    }

    static Map<String, String> error(String code, String message) {
        return Map.of("code", code, "message", message, "traceId", UUID.randomUUID().toString());
    }
}
