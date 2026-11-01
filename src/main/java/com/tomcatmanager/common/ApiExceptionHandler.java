package com.tomcatmanager.common;

import com.tomcatmanager.connectivity.ConnectivityBusyException;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/** Consistent JSON errors for REST controllers. Never exposes stack traces or internal exception text for 5xx. */
@RestControllerAdvice(annotations = RestController.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** {@code link} optionally points the user to a related page, e.g. an existing duplicate release. */
    public record ApiError(int status, String error, String message, Instant timestamp, String reference, String link) { }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiError> notFound(NoSuchElementException e) { return error(HttpStatus.NOT_FOUND, e.getMessage(), null); }

    @ExceptionHandler(ConnectivityBusyException.class)
    ResponseEntity<ApiError> busy(ConnectivityBusyException e) { return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), null); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST, e.getMessage(), null); }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict(IllegalStateException e) { return error(HttpStatus.CONFLICT, e.getMessage(), null); }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(ObjectOptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "Someone else changed this record in the meantime. Reload the page and try again.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Invalid request." : message, null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiError> missing(Exception e) { return error(HttpStatus.BAD_REQUEST, "A required field is missing.", null); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e) { return error(HttpStatus.BAD_REQUEST, "Malformed request.", null); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        String reference = UUID.randomUUID().toString();
        log.error("Unhandled API error {}", reference, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(500, "Internal Server Error", "Unexpected error. Reference: " + reference, Instant.now(), reference, null));
    }

    public static ResponseEntity<ApiError> error(HttpStatus status, String message, String link) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(), DiagnosticSanitizer.clean(message), Instant.now(), null, link));
    }
}
