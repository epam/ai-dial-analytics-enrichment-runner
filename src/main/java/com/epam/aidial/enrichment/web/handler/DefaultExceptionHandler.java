package com.epam.aidial.enrichment.web.handler;

import com.epam.aidial.enrichment.exception.BackfillConflictException;
import com.epam.aidial.enrichment.exception.BackfillRunNotFoundException;
import com.epam.aidial.enrichment.exception.BackfillWindowException;
import com.epam.aidial.enrichment.exception.DlqFilterException;
import com.epam.aidial.enrichment.exception.DlqItemNotFoundException;
import com.epam.aidial.enrichment.exception.DlqNotRequeueableException;
import com.epam.aidial.enrichment.exception.InvalidCursorException;
import com.epam.aidial.enrichment.exception.PipelineCacheNotReadyException;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.exception.UnknownEnumValueException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates uncaught exceptions into the stable {@link ErrorView} JSON contract. Concrete features
 * (pipeline sync, scan, worker, DLQ) add their own handlers as they land, mirroring the ADAS approach.
 */
@RestControllerAdvice
@Slf4j
public class DefaultExceptionHandler {

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public ErrorView handleNotFound(HttpServletRequest req, NoResourceFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", "Resource not found");
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ErrorView handleMethodNotAllowed(HttpServletRequest req, HttpRequestMethodNotSupportedException ex) {
        return new ErrorView(req, HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MissingRequestValueException.class,
    })
    public ErrorView handleBadRequest(HttpServletRequest req, Exception ex) {
        // Never echo ex.getMessage(): Jackson/binding messages leak type names, field paths, and input
        // fragments. Return a stable generic message; the detail is for the server log only.
        log.debug("[{}] Request: {} - rejected as bad request", req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
            "The request could not be processed; check the request body, parameters, and content type");
    }

    /**
     * A parameter that did not convert. Still the generic message, except for a closed vocabulary — a run state, a
     * pause reason, a dead-letter lane — where the refusal names the parameter and its permitted values: that is
     * what makes a misspelled filter correctable. The caller's input is never echoed.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ErrorView handleTypeMismatch(HttpServletRequest req, MethodArgumentTypeMismatchException ex) {
        UnknownEnumValueException unknown = ExceptionUtils.throwableOfType(ex, UnknownEnumValueException.class);
        if (unknown == null) {
            return handleBadRequest(req, ex);
        }
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
            "Parameter '" + ex.getName() + "' must be one of: " + String.join(", ", unknown.getPermitted()));
    }

    /**
     * A backfill verb that needs the pipeline cache arrived before this process had synced it even once. Its own
     * code rather than a {@code 404}: the runner does not yet know whether the pipeline exists.
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(PipelineCacheNotReadyException.class)
    public ErrorView handlePipelineCacheNotReady(HttpServletRequest req, PipelineCacheNotReadyException ex) {
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "pipeline_cache_cold", ex.getMessage());
    }

    /** A dead-letter filter combination that cannot select anything; refused rather than answered empty. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(DlqFilterException.class)
    public ErrorView handleDlqFilter(HttpServletRequest req, DlqFilterException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    /** A DLQ or group listing cursor the listing did not issue; refused rather than restarting the walk. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidCursorException.class)
    public ErrorView handleInvalidCursor(HttpServletRequest req, InvalidCursorException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "invalid_cursor", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(DataAccessException.class)
    public ErrorView handlePostgresUnavailable(HttpServletRequest req, DataAccessException ex) {
        log.warn("[{}] Request: {} - PostgreSQL data access error", req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "postgres_unavailable",
            "The runtime-state database is currently unavailable");
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(DlqItemNotFoundException.class)
    public ErrorView handleDlqItemNotFound(HttpServletRequest req, DlqItemNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(DlqNotRequeueableException.class)
    public ErrorView handleDlqNotRequeueable(HttpServletRequest req, DlqNotRequeueableException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "dlq_not_requeueable", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(BackfillRunNotFoundException.class)
    public ErrorView handleBackfillRunNotFound(HttpServletRequest req, BackfillRunNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(PipelineNotFoundException.class)
    public ErrorView handlePipelineNotFound(HttpServletRequest req, PipelineNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(BackfillConflictException.class)
    public ErrorView handleBackfillConflict(HttpServletRequest req, BackfillConflictException ex) {
        return new ErrorView(req, HttpStatus.CONFLICT, "backfill_conflict", ex.getMessage());
    }

    /**
     * An inverted backfill window. Rejected here rather than by ADAS: waiting for ADAS's 400 would create a
     * run-history row for a run that could only ever fail on its first batch, putting the error far from the
     * operator's mistake.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BackfillWindowException.class)
    public ErrorView handleBackfillWindow(HttpServletRequest req, BackfillWindowException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ErrorView handleGeneralError(HttpServletRequest req, Exception ex) {
        log.warn("[{}] Request: {} raised exception", req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error");
    }
}
