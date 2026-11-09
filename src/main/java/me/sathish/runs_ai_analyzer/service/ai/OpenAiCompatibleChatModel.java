package me.sathish.runs_ai_analyzer.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal chat model for any OpenAI-compatible {@code /v1/chat/completions} endpoint, used for OpenRouter.
 *
 * <p>Built by hand instead of pulling in Spring AI's OpenAI starter: that starter auto-creates a model at
 * startup and fails the whole app when its API key is missing, which is exactly the case while this tier is
 * switched off. This class only exists when a key is configured, and it keeps cost control in one place:
 * a hard {@code max_tokens} on every call, short timeouts, and the token usage of every call in the log.
 *
 * <p>Errors surface as Spring's {@code HttpClientErrorException}/{@code HttpServerErrorException}
 * (401/403 bad key, 402 credits or the key's spend limit used up, 429 rate limited), which
 * {@link AiProviderChain} already knows how to classify.
 */
@Slf4j
public class OpenAiCompatibleChatModel implements ChatModel {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final boolean denyDataCollection;

    /**
     * @param baseUrl            e.g. {@code https://openrouter.ai/api}; the path {@code /v1/chat/completions} is appended
     * @param denyDataCollection ask OpenRouter to route only to providers that do not store or train on prompts
     */
    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, int maxTokens, double temperature,
                                     boolean denyDataCollection, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.denyDataCollection = denyDataCollection;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages(prompt));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (denyDataCollection) {
            body.put("provider", Map.of("data_collection", "deny"));
        }

        String json = restClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return toChatResponse(json);
    }

    private static List<Map<String, String>> messages(Prompt prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String role = switch (message.getMessageType()) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> throw new IllegalArgumentException(
                        "Unsupported message type for an OpenAI-compatible call: " + message.getMessageType());
            };
            messages.add(Map.of("role", role, "content", message.getText()));
        }
        return messages;
    }

    private ChatResponse toChatResponse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("OpenAI-compatible endpoint returned a body that is not JSON", e);
        }
        if (root.hasNonNull("error")) {
            // Some gateways answer 200 with an error object. Never include the request in the message.
            throw new IllegalStateException("OpenAI-compatible endpoint returned an error: "
                    + root.path("error").path("message").asText(root.path("error").toString()));
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        log.info("OpenAI-compatible call done: model={} promptTokens={} completionTokens={}", model,
                root.path("usage").path("prompt_tokens").asText("?"),
                root.path("usage").path("completion_tokens").asText("?"));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
