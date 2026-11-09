package me.sathish.runs_ai_analyzer.config;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the chat failover chain: OpenRouter -> Anthropic -> ACG Ollama -> local Ollama.
 * The local tier always uses the regular spring.ai.ollama settings.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.fallback")
public class AiFallbackProperties {

    private OpenRouter openrouter = new OpenRouter();

    private AcgOllama acgOllama = new AcgOllama();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /**
     * Hosted open-weight models through OpenRouter. Cheap and fast, so it goes first. Spend is bounded three ways:
     * the key's own USD limit set in the OpenRouter dashboard (the real guarantee), {@code dailyCallCap}, and
     * {@code maxTokens} on every answer.
     */
    @Data
    public static class OpenRouter {

        /**
         * OpenRouter API key. Blank disables the tier. Comes from the OPENROUTER_API_KEY stack variable, never a file.
         */
        private String apiKey = "";

        private String baseUrl = "https://openrouter.ai/api";

        /**
         * OpenRouter model slug.
         */
        private String model = "meta-llama/llama-3.3-70b-instruct";

        /**
         * Hard cap on tokens generated per call, which bounds the cost of one call. Sized for the longest reply this
         * chain serves, the structured run analysis (JSON); the coach answer is kept short by its prompt instead.
         * Lower it and that JSON gets cut off.
         */
        private int maxTokens = 1200;

        private double temperature = 0.2;

        /**
         * Most calls attempted per UTC day before this tier is skipped until tomorrow. 0 means unlimited.
         */
        private int dailyCallCap = 200;

        /**
         * Ask OpenRouter to route only to providers that do not store or train on prompts. Your run and journal
         * notes are in the prompt.
         */
        private boolean denyDataCollection = true;

        private Duration connectTimeout = Duration.ofSeconds(3);

        private Duration readTimeout = Duration.ofSeconds(60);
    }

    @Data
    public static class AcgOllama {

        /**
         * Base URL of the ACG sandbox Ollama (usually an SSM port-forward). Blank disables the tier.
         */
        private String baseUrl = "";

        /**
         * Chat model on the ACG Ollama. Blank falls back to spring.ai.ollama.chat.options.model.
         */
        private String chatModel = "";

        /**
         * Kept short so a torn-down sandbox fails fast instead of stalling the request.
         */
        private Duration connectTimeout = Duration.ofSeconds(3);
    }

    @Data
    public static class CircuitBreaker {

        /**
         * How long a tripped breaker stays open before letting a trial call through.
         */
        private Duration waitOpen = Duration.ofSeconds(60);

        /**
         * How long to stop calling a provider after it rejects our credentials (401/403).
         */
        private Duration authFailureOpenDuration = Duration.ofMinutes(30);

        private int slidingWindowSize = 4;

        private int minimumNumberOfCalls = 2;

        private float failureRateThreshold = 50f;
    }
}
