package com.tomcatmanager.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Friendly error pages for server-rendered screens. REST controllers are handled by {@link ApiExceptionHandler}; the few
 * JSON endpoints of page controllers ({@code /api/...}) get the same JSON errors, so the browser scripts can show the message.
 */
@ControllerAdvice(annotations = Controller.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class WebExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    Object notFound(NoSuchElementException e, HttpServletRequest request) {
        if (isApi(request)) return ApiExceptionHandler.error(HttpStatus.NOT_FOUND, e.getMessage(), null);
        ModelAndView view = new ModelAndView("error", Map.of("status", 404, "error", "Not Found", "message", "The requested record does not exist."));
        view.setStatus(HttpStatus.NOT_FOUND);
        return view;
    }

    /** Visible, but the user's team or instance access does not allow this action. */
    @ExceptionHandler(com.tomcatmanager.access.NotPermittedException.class)
    Object forbidden(com.tomcatmanager.access.NotPermittedException e, HttpServletRequest request) {
        String message = e.getMessage() != null ? e.getMessage() : "Your access does not allow this.";
        if (isApi(request)) return ApiExceptionHandler.error(HttpStatus.FORBIDDEN, message, null);
        ModelAndView view = new ModelAndView("error", Map.of("status", 403, "error", "Forbidden", "message", message));
        view.setStatus(HttpStatus.FORBIDDEN);
        return view;
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }
}
