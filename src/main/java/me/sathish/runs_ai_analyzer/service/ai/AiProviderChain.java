package me.sathish.runs_ai_analyzer.service.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.exception.AiAnalysisException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Ordered chat failover: each provider is tried in turn, guarded by its own circuit breaker,
 * so a dead tier (bad Anthropic key, torn-down ACG sandbox) is skipped instead of being
 * re-tried on every request.
 *
 * <p>Auth failures (401/403) and spent credits (402) are treated separately from ordinary errors:
 * a rejected key or an empty balance will not fix itself in 60 seconds, so the breaker is forced open
 * for {@code authFailureOpenDuration}.
 *
 * <p>A provider can also carry a {@code dailyCallLimit}, a cost guard for paid tiers: once that many calls
 * were attempted in a UTC day the tier is skipped until the next day, whatever the provider's own cap allows.
 */
@Slf4j
public class AiProviderChain {

    private final List<AiProvider> providers;
    private final Duration authFailureOpenDuration;
    private final Clock clock;
    private final Map<String, Instant> authBlockedUntil = new ConcurrentHashMap<>();
    private final Map<String, DailyUsage> dailyUsage = new HashMap<>();

    private record DailyUsage(LocalDate day, int calls) {
    }

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
        return callWithDetails(systemPrompt, userPrompt).text();
    }

    /**
     * Same failover as {@link #call}, also reporting which tier answered and which enabled tiers ahead of it
     * were passed over, so the UI can say "answered by local Qwen because OpenRouter hit its daily limit".
     */
    public AiResult callWithDetails(String systemPrompt, String userPrompt) {
        List<String> failures = new ArrayList<>();
        List<AiResult.Skipped> unavailable = new ArrayList<>();

        for (AiProvider provider : providers) {
            if (!provider.enabled()) {
                failures.add(provider.name() + "=disabled (" + provider.disabledReason() + ")");
                continue;
            }
            releaseExpiredAuthBlock(provider);
            if (dailyLimitReached(provider)) {
                failures.add(provider.name() + "=daily limit of " + provider.dailyCallLimit() + " calls reached");
                unavailable.add(new AiResult.Skipped(provider.name(), "daily limit reached"));
                continue;
            }
            CircuitBreaker breaker = provider.breaker();
            if (!breaker.tryAcquirePermission()) {
                failures.add(provider.name() + "=circuit " + breaker.getState());
                unavailable.add(new AiResult.Skipped(provider.name(), "skipped after repeated failures"));
                continue;
            }
            recordCall(provider);

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
                return new AiResult(response, provider.name(), List.copyOf(unavailable));
            } catch (Exception ex) {
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
                if (isAuthFailure(ex) || isCreditFailure(ex)) {
                    authBlockedUntil.put(provider.name(), clock.instant().plus(authFailureOpenDuration));
                    breaker.transitionToForcedOpenState();
                    log.warn("AI provider '{}' {}; skipping it for {}", provider.name(),
                            isAuthFailure(ex) ? "rejected credentials" : "has no credits or hit its spend limit",
                            authFailureOpenDuration);
                } else {
                    log.warn("AI provider '{}' failed ({}), trying next provider", provider.name(), ex.getMessage());
                }
                failures.add(provider.name() + "=" + ex.getMessage());
                unavailable.add(new AiResult.Skipped(provider.name(), friendlyReason(ex)));
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

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private synchronized boolean dailyLimitReached(AiProvider provider) {
        if (provider.dailyCallLimit() <= 0) {
            return false;
        }
        DailyUsage usage = dailyUsage.get(provider.name());
        return usage != null && usage.day().equals(today()) && usage.calls() >= provider.dailyCallLimit();
    }

    private synchronized void recordCall(AiProvider provider) {
        if (provider.dailyCallLimit() <= 0) {
            return;
        }
        LocalDate today = today();
        DailyUsage usage = dailyUsage.get(provider.name());
        int calls = usage != null && usage.day().equals(today) ? usage.calls() : 0;
        dailyUsage.put(provider.name(), new DailyUsage(today, calls + 1));
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

    /**
     * HTTP 402: the account is out of credits or the API key reached its own spend limit
     * (OpenRouter reports its per-key cap this way). It stays true until credits are added or the
     * cap resets, so it is handled like a rejected key rather than retried every minute.
     */
    static boolean isCreditFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof HttpClientErrorException http && http.getStatusCode().value() == 402) {
                return true;
            }
            if (t instanceof NonTransientAiException && t.getMessage() != null && t.getMessage().startsWith("402")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Short wording that is safe to show a user: never raw exception text, which can contain URLs or response bodies.
     */
    static String friendlyReason(Throwable ex) {
        if (isAuthFailure(ex)) {
            return "credentials rejected";
        }
        if (isCreditFailure(ex)) {
            return "credits or key spend limit used up";
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusCodeException http) {
                int status = http.getStatusCode().value();
                if (status == 429) {
                    return "rate limited";
                }
                if (status >= 500) {
                    return "provider error";
                }
            }
            if (t instanceof ResourceAccessException) {
                return "not reachable";
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return "failed";
    }
}
