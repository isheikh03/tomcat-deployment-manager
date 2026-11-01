package com.tomcatmanager.common;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.NoSuchElementException;

/** Friendly error pages for server-rendered screens. REST controllers are handled by {@link ApiExceptionHandler}. */
@ControllerAdvice(annotations = Controller.class)
@Order(Ordered.LOWEST_PRECEDENCE)
public class WebExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ModelAndView notFound(NoSuchElementException e) {
        ModelAndView view = new ModelAndView("error", Map.of("status", 404, "error", "Not Found", "message", "The requested record does not exist."));
        view.setStatus(HttpStatus.NOT_FOUND);
        return view;
    }
}
