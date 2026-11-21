package com.iitsm.web.error;

import com.iitsm.domain.exception.ConcurrentUpdateException;
import com.iitsm.domain.exception.DuplicateIncidentException;
import com.iitsm.domain.exception.IitsmException;
import com.iitsm.domain.exception.InvalidCredentialsException;
import com.iitsm.domain.exception.InvalidMfaCodeException;
import com.iitsm.domain.exception.InvalidRefreshTokenException;
import com.iitsm.domain.exception.MfaAlreadyEnabledException;
import com.iitsm.domain.exception.MfaNotEnrolledException;
import com.iitsm.domain.exception.InvalidStateTransitionException;
import com.iitsm.domain.exception.NotInTriageException;
import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.exception.ReassignmentRequestNotPendingException;
import com.iitsm.domain.exception.ServiceBusyException;
import com.iitsm.domain.exception.ResourceNotFoundException;
import com.iitsm.domain.exception.NotResolvedException;
import com.iitsm.domain.exception.AlreadyPromotedException;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.TechnicianOverloadedException;
import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.EnumText;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized exception handler mapping all IitsmException hierarchy instances
 * and standard web validation failures into uniform, RFC-compliant JSON error representations.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Error codes of the standard web errors (ADR-027); the domain errors carry their own codes. */
    static final String CODE_INVALID_PARAMETER = "ERR_INVALID_PARAMETER";
    static final String CODE_MISSING_PARAMETER = "ERR_MISSING_PARAMETER";
    static final String CODE_METHOD_NOT_ALLOWED = "ERR_METHOD_NOT_ALLOWED";
    static final String CODE_UNSUPPORTED_MEDIA_TYPE = "ERR_UNSUPPORTED_MEDIA_TYPE";
    static final String CODE_NOT_FOUND = "ERR_NOT_FOUND";

    public record FieldErrorDetail(String field, String message) {}

    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors) {}

    @ExceptionHandler(IitsmException.class)
    public ResponseEntity<ErrorResponse> handleIitsmException(IitsmException ex) {
        if (isConcurrencyConflict(ex)) {
            return conflictResponse(ex);
        }
        HttpStatus status = resolveStatus(ex);
        log.warn("Domain exception caught [{}]: {}", ex.getErrorCode(), ex.getMessage());

        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                ex.getErrorCode(),
                ex.getMessage(),
                Collections.emptyList());

        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "ERR_MALFORMED_JSON",
                "Malformed request body or unparseable JSON payload",
                Collections.emptyList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldErrorDetail(f.getField(), f.getDefaultMessage()))
                .collect(Collectors.toList());

        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "ERR_VALIDATION_FAILED",
                "Input validation failed for one or more fields",
                errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "ERR_FORBIDDEN",
                ex.getMessage(),
                Collections.emptyList());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "ERR_UNAUTHORIZED",
                ex.getMessage(),
                Collections.emptyList());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * A write lost a race with another write (ADR-017). Spring raises this at commit, after the service method has
     * returned, so it can only be handled here; conflicts thrown inside a query arrive wrapped in a
     * PersistenceFailureException and are unwrapped by {@link #isConcurrencyConflict}.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        return conflictResponse(ex);
    }

    private static boolean isConcurrencyConflict(IitsmException ex) {
        if (ex instanceof ConcurrentUpdateException) {
            return true;
        }
        for (Throwable cause = ex.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ConcurrencyFailureException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ErrorResponse> conflictResponse(Exception ex) {
        // Expected condition: no stack trace, and the user-facing text never carries entity internals.
        log.warn("Concurrent update conflict: {}", ex.getClass().getSimpleName());
        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ConcurrentUpdateException.ERROR_CODE,
                ConcurrentUpdateException.USER_MESSAGE,
                Collections.emptyList());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * A request parameter or path variable of the wrong type: an unknown enum value, text where a number or date is
     * expected. It is the clients mistake, so it is a 400 that names the parameter and, for an enum, the valid values.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Parameter '" + ex.getName() + "' has an invalid value"
                + (ex.getValue() == null ? "" : " '" + EnumText.echo(String.valueOf(ex.getValue())) + "'")
                + validValuesHint(enumTypeOf(ex));
        log.debug("Rejected request: {}", message);
        return error(HttpStatus.BAD_REQUEST, CODE_INVALID_PARAMETER, message,
                List.of(new FieldErrorDetail(ex.getName(), message)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = "Required parameter '" + ex.getParameterName() + "' is missing";
        return error(HttpStatus.BAD_REQUEST, CODE_MISSING_PARAMETER, message,
                List.of(new FieldErrorDetail(ex.getParameterName(), message)));
    }

    /** The endpoint exists but not for this HTTP method: 405 with the methods it does accept. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        String allowed = headers.getAllow().isEmpty() ? "" : " Allowed: " + headers.getAllow().stream()
                .map(Object::toString).collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(errorBody(
                HttpStatus.METHOD_NOT_ALLOWED, CODE_METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this endpoint." + allowed, Collections.emptyList()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CODE_UNSUPPORTED_MEDIA_TYPE,
                "The content type of the request is not supported; use application/json", Collections.emptyList());
    }

    /** No handler and no static file for the path (for example the favicon a browser asks for): 404, not a server error. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, CODE_NOT_FOUND, "Resource not found", Collections.emptyList());
    }

    /** The enum behind a mismatched parameter, also when it is the element type of a Set or List parameter. */
    private static Class<?> enumTypeOf(MethodArgumentTypeMismatchException ex) {
        Class<?> type = ex.getRequiredType();
        if (type != null && Collection.class.isAssignableFrom(type)) {
            type = ResolvableType.forMethodParameter(ex.getParameter()).asCollection().resolveGeneric(0);
        }
        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String validValuesHint(Class<?> type) {
        return (type != null && type.isEnum()) ? ". Valid values: " + EnumText.validValues((Class) type) : "";
    }

    private static ErrorResponse errorBody(HttpStatus status, String code, String message, List<FieldErrorDetail> fieldErrors) {
        return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), code, message, fieldErrors);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, List<FieldErrorDetail> fieldErrors) {
        return ResponseEntity.status(status).body(errorBody(status, code, message, fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled server exception: {}", ex.getMessage(), ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "ERR_INTERNAL_SERVER_ERROR",
                "An unexpected server error occurred",
                Collections.emptyList());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private HttpStatus resolveStatus(IitsmException ex) {
        if (ex instanceof UnauthorizedOperationException) {
            return HttpStatus.FORBIDDEN;
        } else if (ex instanceof InvalidCredentialsException
                || ex instanceof InvalidRefreshTokenException
                || ex instanceof InvalidMfaCodeException) {
            return HttpStatus.UNAUTHORIZED;
        } else if (ex instanceof ServiceBusyException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        } else if (ex instanceof ServiceNotFoundException || ex instanceof ResourceNotFoundException) {
            return HttpStatus.NOT_FOUND;
        } else if (ex instanceof InvalidStateTransitionException
                || ex instanceof NotInTriageException
                || ex instanceof ReassignmentRequestNotPendingException
                || ex instanceof NotResolvedException
                || ex instanceof AlreadyPromotedException
                || ex instanceof MfaAlreadyEnabledException
                || ex instanceof MfaNotEnrolledException
                || ex instanceof DuplicateIncidentException
                || ex instanceof TechnicianOverloadedException
                || ex instanceof TechnicianUnavailableException) {
            return HttpStatus.CONFLICT;
        } else if (ex instanceof PersistenceFailureException) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        } else {
            return HttpStatus.BAD_REQUEST;
        }
    }
}

