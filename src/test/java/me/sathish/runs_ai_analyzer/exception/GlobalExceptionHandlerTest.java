package me.sathish.runs_ai_analyzer.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void scannerProbeForAMissingPathIsA404LoggedAtDebugWithNoErrorOrStackTrace() {
        logger.setLevel(Level.DEBUG);

        ProblemDetail problem = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/wp-login.php", "wp-login.php"));

        assertEquals(404, problem.getStatus());
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO)),
                "a 404 must not log at INFO or above");
        assertTrue(appender.list.stream().noneMatch(e -> e.getThrowableProxy() != null),
                "a 404 must not log a stack trace");
    }

    @Test
    void springMvcClientErrorKeepsItsStatusInsteadOfBecomingA500() {
        ProblemDetail problem = handler.handleGenericException(new HttpRequestMethodNotSupportedException("DELETE"));

        assertEquals(405, problem.getStatus());
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "a client error must not log at ERROR");
    }

    @Test
    void trulyUnexpectedExceptionIsStillA500WithAnErrorLog() {
        ProblemDetail problem = handler.handleGenericException(new IllegalStateException("boom"));

        assertEquals(500, problem.getStatus());
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
    }

    @Test
    void unreadableJsonBodyIsA400WithAWarnLogNotA500() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('}')", mock(HttpInputMessage.class));

        ProblemDetail problem = handler.handleUnreadableBody(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("Malformed Request Body", problem.getTitle());
        assertEquals(1, appender.list.size());
        ILoggingEvent logged = appender.list.get(0);
        assertEquals(Level.WARN, logged.getLevel());
        assertTrue(logged.getFormattedMessage().contains("Unexpected character"),
                "the real parse error must be in the log: " + logged.getFormattedMessage());
    }
}
