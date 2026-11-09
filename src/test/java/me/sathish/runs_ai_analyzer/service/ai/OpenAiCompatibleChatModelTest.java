package me.sathish.runs_ai_analyzer.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talks to a real local HTTP server standing in for OpenRouter, so the request shape (path, auth header, the
 * cost-bounding max_tokens, the privacy flag) and the error handling are checked on the wire.
 */
class OpenAiCompatibleChatModelTest {

    private static final String OK_BODY = """
            {"id":"gen-1","choices":[{"message":{"role":"assistant","content":"Keep it easy this week."}}],
             "usage":{"prompt_tokens":700,"completion_tokens":120}}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> seenRequestLine = new AtomicReference<>();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, String responseBody, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            seenRequestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/api";
    }

    private static OpenAiCompatibleChatModel model(String baseUrl, boolean denyDataCollection, Duration readTimeout) {
        return new OpenAiCompatibleChatModel(baseUrl, "sk-or-test", "meta-llama/llama-3.3-70b-instruct", 500, 0.2,
                denyDataCollection, Duration.ofSeconds(1), readTimeout);
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("SYS"), new UserMessage("USR")));
    }

    @Test
    void sendsAnOpenRouterShapedRequestAndReturnsTheAnswer() throws Exception {
        String baseUrl = start(200, OK_BODY, 0);

        String answer = ChatClient.create(model(baseUrl, true, Duration.ofSeconds(5)))
                .prompt().system("SYS").user("USR").call().content();

        assertThat(answer).isEqualTo("Keep it easy this week.");
        assertThat(seenRequestLine.get()).isEqualTo("POST /api/v1/chat/completions");
        assertThat(seenAuthorization.get()).isEqualTo("Bearer sk-or-test");
        JsonNode body = mapper.readTree(seenBody.get());
        assertThat(body.path("model").asText()).isEqualTo("meta-llama/llama-3.3-70b-instruct");
        assertThat(body.path("max_tokens").asInt()).as("every call is bounded").isEqualTo(500);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("SYS");
        assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("USR");
    }

    @Test
    void doesNotSendThePrivacyFlagWhenItIsSwitchedOff() throws Exception {
        String baseUrl = start(200, OK_BODY, 0);

        model(baseUrl, false, Duration.ofSeconds(5)).call(prompt());

        assertThat(mapper.readTree(seenBody.get()).has("provider")).isFalse();
    }

    @Test
    void aRejectedKeyIsClassifiedAsAnAuthFailure() throws Exception {
        String baseUrl = start(401, "{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}", 0);

        assertThatThrownBy(() -> model(baseUrl, true, Duration.ofSeconds(5)).call(prompt()))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(AiProviderChain.isAuthFailure(ex)).isTrue());
    }

    @Test
    void spentCreditsOrAKeyLimitIsClassifiedAsACreditFailure() throws Exception {
        String baseUrl = start(402, "{\"error\":{\"code\":402,\"message\":\"Key limit exceeded\"}}", 0);

        assertThatThrownBy(() -> model(baseUrl, true, Duration.ofSeconds(5)).call(prompt()))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(AiProviderChain.isCreditFailure(ex)).isTrue())
                .satisfies(ex -> assertThat(AiProviderChain.friendlyReason(ex))
                        .isEqualTo("credits or key spend limit used up"));
    }

    @Test
    void aSuccessStatusWithAnErrorBodyIsAFailureNotAnEmptyAnswer() throws Exception {
        String baseUrl = start(200, "{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}", 0);

        assertThatThrownBy(() -> model(baseUrl, true, Duration.ofSeconds(5)).call(prompt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Provider returned error");
    }

    @Test
    void aSlowEndpointFailsFastAsNotReachable() throws Exception {
        String baseUrl = start(200, OK_BODY, 2000);

        long started = System.nanoTime();
        assertThatThrownBy(() -> model(baseUrl, true, Duration.ofMillis(300)).call(prompt()))
                .satisfies(ex -> assertThat(AiProviderChain.friendlyReason(ex)).isEqualTo("not reachable"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
