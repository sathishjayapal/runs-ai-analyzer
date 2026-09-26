package me.sathish.runs_ai_analyzer.config;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import me.sathish.runs_ai_analyzer.service.ai.AiProvider;
import me.sathish.runs_ai_analyzer.service.ai.AiProviderChain;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    static final String ANTHROPIC = "anthropic";
    static final String ACG_OLLAMA = "acg-ollama";
    static final String LOCAL_OLLAMA = "local-ollama";

    @Bean
    @Primary
    @Qualifier("anthropicChatClient")
    public ChatClient anthropicChatClient(AnthropicChatModel anthropicChatModel) {
        return ChatClient.builder(anthropicChatModel).build();
    }

    @Bean
    @Qualifier("ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    /**
     * Explicit OllamaApi with a hard-coded socket read timeout. The autoconfigured
     * RestClient.Builder was not honoring spring.http.clients.read-timeout in practice
     * (calls were failing after ~9s instead of OLLAMA_CHAT_TIMEOUT), so this builds the
     * HttpComponents request factory directly to guarantee the timeout applies.
     */
    @Bean
    public OllamaApi ollamaApi(
            @Value("${OLLAMA_BASE_URL}") String baseUrl,
            @DurationUnit(ChronoUnit.SECONDS) @Value("${OLLAMA_CHAT_TIMEOUT:600}") Duration readTimeout) {
        return buildOllamaApi(baseUrl, Duration.ofSeconds(10), readTimeout);
    }

    /**
     * Failover order: Anthropic -> ACG sandbox Ollama -> local Ollama. Embeddings are
     * deliberately not part of this chain; they stay on OLLAMA_BASE_URL so pgvector never
     * mixes vectors from different Ollama instances.
     */
    @Bean
    public AiProviderChain aiProviderChain(
            @Qualifier("anthropicChatClient") ChatClient anthropicChatClient,
            @Qualifier("ollamaChatClient") ChatClient localOllamaChatClient,
            AiFallbackProperties fallback,
            Environment environment,
            @Value("${spring.ai.ollama.chat.options.model:}") String localChatModel,
            @DurationUnit(ChronoUnit.SECONDS) @Value("${OLLAMA_CHAT_TIMEOUT:600}") Duration readTimeout) {

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(fallback.getCircuitBreaker().getSlidingWindowSize())
                .minimumNumberOfCalls(fallback.getCircuitBreaker().getMinimumNumberOfCalls())
                .failureRateThreshold(fallback.getCircuitBreaker().getFailureRateThreshold())
                .waitDurationInOpenState(fallback.getCircuitBreaker().getWaitOpen())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        String anthropicKeyProblem = anthropicKeyProblem(resolveAnthropicKey(environment));
        AiProvider anthropic = anthropicKeyProblem == null
                ? AiProvider.enabled(ANTHROPIC, anthropicChatClient, CircuitBreaker.of(ANTHROPIC, breakerConfig))
                : AiProvider.disabled(ANTHROPIC, CircuitBreaker.of(ANTHROPIC, breakerConfig), anthropicKeyProblem);

        AiFallbackProperties.AcgOllama acg = fallback.getAcgOllama();
        AiProvider acgOllama;
        if (StringUtils.hasText(acg.getBaseUrl())) {
            String model = StringUtils.hasText(acg.getChatModel()) ? acg.getChatModel() : localChatModel;
            OllamaChatModel acgModel = OllamaChatModel.builder()
                    .ollamaApi(buildOllamaApi(acg.getBaseUrl(), acg.getConnectTimeout(), readTimeout))
                    .defaultOptions(OllamaChatOptions.builder().model(model).build())
                    // No retries: the default template retries connection errors with backoff
                    // for minutes, which defeats failing over fast when the sandbox is gone.
                    .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                    .build();
            acgOllama = AiProvider.enabled(ACG_OLLAMA, ChatClient.create(acgModel),
                    CircuitBreaker.of(ACG_OLLAMA, breakerConfig));
        } else {
            acgOllama = AiProvider.disabled(ACG_OLLAMA, CircuitBreaker.of(ACG_OLLAMA, breakerConfig),
                    "ai.fallback.acg-ollama.base-url (ACG_OLLAMA_BASE_URL) not set");
        }

        AiProvider localOllama = AiProvider.enabled(LOCAL_OLLAMA, localOllamaChatClient,
                CircuitBreaker.of(LOCAL_OLLAMA, breakerConfig));

        return new AiProviderChain(List.of(anthropic, acgOllama, localOllama),
                fallback.getCircuitBreaker().getAuthFailureOpenDuration(), Clock.systemUTC());
    }

    /**
     * The VM compose doesn't always pass ANTHROPIC_API_KEY, leaving "${ANTHROPIC_API_KEY}"
     * unresolved in the config-server yml. Strict placeholder resolution would throw and
     * kill startup, so treat that as "no key" and let the chain skip Anthropic.
     */
    static String resolveAnthropicKey(Environment environment) {
        try {
            return environment.getProperty("spring.ai.anthropic.api-key", "");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return "";
        }
    }

    /**
     * Returns why the key is unusable, or null if it looks like a real key. Never logs the key.
     */
    static String anthropicKeyProblem(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "ANTHROPIC_API_KEY is not set";
        }
        String key = apiKey.strip();
        if (!key.startsWith("sk-ant-") || key.contains("<") || key.toLowerCase().contains("changeme")) {
            return "ANTHROPIC_API_KEY does not look like a valid Anthropic key";
        }
        return null;
    }

    private static OllamaApi buildOllamaApi(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.httpComponents()
                .build(HttpClientSettings.defaults().withTimeouts(connectTimeout, readTimeout));
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }
}
