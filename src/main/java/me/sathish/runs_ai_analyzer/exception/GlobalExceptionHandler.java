package me.sathish.runs_ai_analyzer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        log.warn("Validation error: {}", ex.getMessage());

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, errors);
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("https://api.runs-ai-analyzer.me/errors/validation"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    /**
     * A body that is missing or not parseable as the expected JSON. Without this it fell through to the
     * catch-all below and came back as a 500 "Internal Server Error" with no hint of the real cause.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body (400): {}", ex.getMostSpecificCause().getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON for this endpoint");
        problem.setTitle("Malformed Request Body");
        problem.setType(URI.create("https://api.runs-ai-analyzer.me/errors/malformed-body"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(AiAnalysisException.class)
    public ProblemDetail handleAiAnalysisException(AiAnalysisException ex) {
        log.error("AI analysis error: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("AI Analysis Failed");
        problem.setType(URI.create("https://api.runs-ai-analyzer.me/errors/ai-failure"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    /**
     * A request for a path nothing serves. Internet scanners hit this hundreds of times a day
     * (/wp-login.php, /manager/html, /.git/HEAD, ...), and it used to fall through to the catch-all
     * below: an ERROR line with a full stack trace and a 500 for each one. It is a plain 404, logged
     * at DEBUG so it neither floods the logs nor looks like an application fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource (404): {}", ex.getResourcePath());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        problem.setTitle("Not Found");
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        // Spring MVC's own client errors (405 wrong method, 415 wrong media type, ...) carry their own
        // status; answer with it instead of turning them into a 500 with an ERROR stack trace.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            log.warn("Client error {}: {}", errorResponse.getStatusCode().value(), ex.getMessage());
            return errorResponse.getBody();
        }
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://api.runs-ai-analyzer.me/errors/internal"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }
}
