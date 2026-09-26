package me.sathish.runs_ai_analyzer.config;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the chat failover chain: Anthropic -> ACG Ollama -> local Ollama.
 * The local tier always uses the regular spring.ai.ollama settings.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.fallback")
public class AiFallbackProperties {

    private AcgOllama acgOllama = new AcgOllama();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

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
