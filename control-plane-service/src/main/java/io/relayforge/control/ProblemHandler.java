package io.relayforge.control;

import io.relayforge.security.OutboundTargetPolicy;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemHandler {
    @ExceptionHandler({IllegalArgumentException.class, OutboundTargetPolicy.BlockedTargetException.class})
    ProblemDetail badRequest(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage(), "invalid-request");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage(), "not-found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "The requested operation is not permitted", "forbidden");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(title);
        result.setType(URI.create("https://relayforge.local/problems/" + type));
        return result;
    }
}
