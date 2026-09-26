package me.sathish.runs_ai_analyzer.service.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.exception.AiAnalysisException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Ordered chat failover: each provider is tried in turn, guarded by its own circuit breaker,
 * so a dead tier (bad Anthropic key, torn-down ACG sandbox) is skipped instead of being
 * re-tried on every request.
 *
 * <p>Auth failures (401/403) are treated separately from ordinary errors: a rejected key will
 * not fix itself in 60 seconds, so the breaker is forced open for {@code authFailureOpenDuration}.
 */
@Slf4j
public class AiProviderChain {

    private final List<AiProvider> providers;
    private final Duration authFailureOpenDuration;
    private final Clock clock;
    private final Map<String, Instant> authBlockedUntil = new ConcurrentHashMap<>();

    public AiProviderChain(List<AiProvider> providers, Duration authFailureOpenDuration, Clock clock) {
        this.providers = List.copyOf(providers);
        this.authFailureOpenDuration = authFailureOpenDuration;
        this.clock = clock;
        providers.stream()
                .filter(p -> !p.enabled())
                .forEach(p -> log.warn("AI provider '{}' disabled: {}", p.name(), p.disabledReason()));
    }

    public List<AiProvider> providers() {
        return providers;
    }

    public String call(String systemPrompt, String userPrompt) {
        List<String> failures = new ArrayList<>();

        for (AiProvider provider : providers) {
            if (!provider.enabled()) {
                failures.add(provider.name() + "=disabled (" + provider.disabledReason() + ")");
                continue;
            }
            releaseExpiredAuthBlock(provider);
            CircuitBreaker breaker = provider.breaker();
            if (!breaker.tryAcquirePermission()) {
                failures.add(provider.name() + "=circuit " + breaker.getState());
                continue;
            }

            long start = System.nanoTime();
            try {
                String response = provider.client().prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
                if (response == null || response.isBlank()) {
                    throw new AiAnalysisException(provider.name() + " returned an empty response");
                }
                breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                log.info("AI analysis served by {}", provider.name());
                return response;
            } catch (Exception ex) {
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
                if (isAuthFailure(ex)) {
                    authBlockedUntil.put(provider.name(), clock.instant().plus(authFailureOpenDuration));
                    breaker.transitionToForcedOpenState();
                    log.warn("AI provider '{}' rejected credentials; skipping it for {}",
                            provider.name(), authFailureOpenDuration);
                } else {
                    log.warn("AI provider '{}' failed ({}), trying next provider", provider.name(), ex.getMessage());
                }
                failures.add(provider.name() + "=" + ex.getMessage());
            }
        }

        throw new AiAnalysisException("All AI providers failed: " + String.join("; ", failures));
    }

    private void releaseExpiredAuthBlock(AiProvider provider) {
        Instant until = authBlockedUntil.get(provider.name());
        if (until != null && !clock.instant().isBefore(until)) {
            authBlockedUntil.remove(provider.name());
            provider.breaker().transitionToClosedState();
            log.info("AI provider '{}' auth block expired; retrying it", provider.name());
        }
    }

    static boolean isAuthFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof HttpClientErrorException http) {
                int status = http.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return true;
                }
            }
            // Spring AI's default error handler formats 4xx as "<status> - <body>"
            if (t instanceof NonTransientAiException && t.getMessage() != null
                    && (t.getMessage().startsWith("401") || t.getMessage().startsWith("403"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
