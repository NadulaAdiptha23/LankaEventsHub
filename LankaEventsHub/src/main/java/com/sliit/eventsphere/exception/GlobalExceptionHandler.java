package com.sliit.eventsphere.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into clean responses instead of raw stack traces.
 *
 * The important detail is that this advice serves BOTH kinds of client.
 * It used to be a @RestControllerAdvice, which meant an ordinary business
 * error on a Thymeleaf page (say, registering twice) was answered with a
 * blob of JSON in the browser. Now each handler asks whether the current
 * request is an API call - /api/** or an Accept header asking for JSON -
 * and returns either a JSON body or a rendered error page accordingly.
 *
 * No handler ever puts an exception class name, SQL fragment, or stack
 * trace in the response; the full detail goes to the server log instead.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- 404 ---------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public Object handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // --- 409 (checked before its 400 superclass) ----------------------

    @ExceptionHandler(ConflictException.class)
    public Object handleConflict(ConflictException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT, ex.getMessage());
    }

    // --- 400 ---------------------------------------------------------

    @ExceptionHandler(InvalidRequestException.class)
    public Object handleInvalid(InvalidRequestException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return respond(request, HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Object handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST,
                "Required field '" + ex.getParameterName() + "' is missing");
    }

    /**
     * Malformed JSON, or a value that does not fit the target type - most
     * often an invalid enum value such as {"eventType": "SECRET"}. The
     * cause chain would name internal classes, so only a short, safe
     * description is returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body", ex);
        return respond(request, HttpStatus.BAD_REQUEST,
                "The request body could not be read. Check the JSON syntax and that every "
                        + "value is one of the allowed options.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST,
                "'" + ex.getName() + "' has an invalid value");
    }

    /**
     * A database constraint refused the write - a duplicate registration
     * racing another one, a second payment for the same registration, and
     * so on. These are conflicts, not server faults, and the constraint
     * name (which would leak schema detail) is deliberately not returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Object handleConstraint(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Database constraint violation on {}", request.getRequestURI(), ex);
        return respond(request, HttpStatus.CONFLICT,
                "That action conflicts with existing data and was not applied.");
    }

    // --- 401 / 403 ---------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    public Object handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    /**
     * Without this, a failed authorization check on an /api/** endpoint
     * falls through to Spring Security's default handling, which is built
     * for browser flows, not a JSON API. The message is one of ours (see
     * EventAccessPolicy and the services), so it is safe to pass through
     * and far more useful than a bare 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String message = (ex.getMessage() == null || ex.getMessage().isBlank())
                ? "You do not have permission to perform this action."
                : ex.getMessage();
        return respond(request, HttpStatus.FORBIDDEN, message);
    }

    // --- protocol-level errors ---------------------------------------
    //
    // Without these, Spring's own exceptions for "wrong verb", "wrong
    // content type" and "no such path" fell through to the catch-all
    // below and came back as 500. A 500 tells the client the server
    // broke; every one of these is the request being wrong, and each has
    // a status that says so.

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                           HttpServletRequest request) {
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED,
                "The " + ex.getMethod() + " method is not supported for this endpoint.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Object handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                              HttpServletRequest request) {
        return respond(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "This endpoint does not accept that content type.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public Object handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                      HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_ACCEPTABLE,
                "This endpoint cannot produce any of the requested content types.");
    }

    /**
     * Spring Boot 3 raises this when a URL matches no handler and no
     * static resource. It is a 404, not a server fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, "That page could not be found.");
    }

    /**
     * Registration and cancellation take PESSIMISTIC_WRITE locks on the
     * event and registration rows. Under contention the database can time
     * out waiting for one. That is a transient conflict the caller can
     * retry, not an internal error, so it answers 409 and says to retry.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class,
                       OptimisticLockingFailureException.class,
                       CannotAcquireLockException.class})
    public Object handleLockFailure(Exception ex, HttpServletRequest request) {
        log.warn("Lock contention on {} {}: {}", request.getMethod(),
                request.getRequestURI(), ex.getMessage());
        return respond(request, HttpStatus.CONFLICT,
                "That event is being updated by someone else right now. Please try again.");
    }

    // --- 500 ---------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again.");
    }

    // --- shared ------------------------------------------------------

    private Object respond(HttpServletRequest request, HttpStatus status, String message) {
        return wantsJson(request) ? json(status, message) : page(status, message);
    }

    private boolean wantsJson(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private ResponseEntity<Object> json(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private ModelAndView page(HttpStatus status, String message) {
        ModelAndView mav = new ModelAndView("error");
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("error", status.getReasonPhrase());
        mav.addObject("message", message);
        return mav;
    }
}
