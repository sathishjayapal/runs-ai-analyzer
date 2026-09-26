package me.sathish.runs_ai_analyzer.service.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.ai.chat.client.ChatClient;

/**
 * One tier in the {@link AiProviderChain}. A disabled provider (no API key, no URL) is
 * kept in the chain so it still shows up in health output, but is never called.
 */
public record AiProvider(String name, ChatClient client, CircuitBreaker breaker, boolean enabled,
                         String disabledReason) {

    public static AiProvider enabled(String name, ChatClient client, CircuitBreaker breaker) {
        return new AiProvider(name, client, breaker, true, null);
    }

    public static AiProvider disabled(String name, CircuitBreaker breaker, String reason) {
        return new AiProvider(name, null, breaker, false, reason);
    }
}
