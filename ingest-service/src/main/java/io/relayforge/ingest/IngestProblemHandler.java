package io.relayforge.ingest;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class IngestProblemHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid event", exception.getMessage(), "invalid-event");
    }
    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail conflict() {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict",
                "This idempotency key was already used with a different request", "idempotency-conflict");
    }
    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail tooLarge(PayloadTooLargeException exception) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Payload too large",
                "Payload exceeds " + exception.maximum() + " bytes", "payload-too-large");
    }
    @ExceptionHandler(IngestNotFoundException.class)
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "Event not found", "No event was found for this tenant", "not-found");
    }
    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(title);
        result.setType(URI.create("https://relayforge.local/problems/" + type));
        return result;
    }
}
