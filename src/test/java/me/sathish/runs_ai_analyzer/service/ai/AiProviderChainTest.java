package me.sathish.runs_ai_analyzer.service.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import me.sathish.runs_ai_analyzer.exception.AiAnalysisException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProviderChainTest {

    private static final Duration AUTH_BLOCK = Duration.ofMinutes(30);
    private static final Duration WAIT_OPEN = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock();

    @Test
    void disabledAnthropic_isNeverCalled_andAcgServes() {
        ChatClient acg = clientReturning("acg-answer");
        ChatClient local = clientReturning("local-answer");
        AiProviderChain chain = chain(
                AiProvider.disabled("anthropic", breaker("anthropic"), "ANTHROPIC_API_KEY is not set"),
                AiProvider.enabled("acg-ollama", acg, breaker("acg-ollama")),
                AiProvider.enabled("local-ollama", local, breaker("local-ollama")));

        assertThat(chain.call("sys", "user")).isEqualTo("acg-answer");
        verify(local, never()).prompt();
    }

    @Test
    void unauthorizedAnthropic_isSkippedWithoutInvocationUntilAuthBlockExpires() {
        ChatClient anthropic = clientThrowing(new NonTransientAiException("401 - invalid x-api-key"));
        ChatClient local = clientReturning("local-answer");
        CircuitBreaker anthropicBreaker = breaker("anthropic");
        AiProviderChain chain = chain(
                AiProvider.enabled("anthropic", anthropic, anthropicBreaker),
                AiProvider.enabled("local-ollama", local, breaker("local-ollama")));

        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
        assertThat(anthropicBreaker.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

        // Well past the normal 60s wait: still skipped because a bad key doesn't heal itself.
        clock.advance(Duration.ofMinutes(10));
        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
        verify(anthropic, times(1)).prompt();

        clock.advance(AUTH_BLOCK);
        chain.call("sys", "user");
        verify(anthropic, times(2)).prompt();
    }

    @Test
    void acgConnectionFailures_openBreaker_andLocalServes() {
        ChatClient acg = clientThrowing(new ResourceAccessException("Connect timed out"));
        ChatClient local = clientReturning("local-answer");
        CircuitBreaker acgBreaker = breaker("acg-ollama");
        AiProviderChain chain = chain(
                AiProvider.enabled("acg-ollama", acg, acgBreaker),
                AiProvider.enabled("local-ollama", local, breaker("local-ollama")));

        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
        assertThat(acgBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
        verify(acg, times(2)).prompt();
    }

    @Test
    void openBreaker_letsTrialCallThroughAfterWait_andClosesOnSuccess() {
        ChatClient acg = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(acg.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new ResourceAccessException("down"))
                .thenThrow(new ResourceAccessException("down"))
                .thenReturn("acg-back");
        CircuitBreaker acgBreaker = breaker("acg-ollama");
        AiProviderChain chain = chain(
                AiProvider.enabled("acg-ollama", acg, acgBreaker),
                AiProvider.enabled("local-ollama", clientReturning("local-answer"), breaker("local-ollama")));

        chain.call("sys", "user");
        chain.call("sys", "user");
        assertThat(acgBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(WAIT_OPEN.plusSeconds(1));
        assertThat(chain.call("sys", "user")).isEqualTo("acg-back");
        assertThat(acgBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void blankResponse_countsAsFailure_andFallsThrough() {
        AiProviderChain chain = chain(
                AiProvider.enabled("anthropic", clientReturning("  "), breaker("anthropic")),
                AiProvider.enabled("local-ollama", clientReturning("local-answer"), breaker("local-ollama")));

        assertThat(chain.call("sys", "user")).isEqualTo("local-answer");
    }

    @Test
    void allProvidersFail_throwsWithEveryTierNamed() {
        AiProviderChain chain = chain(
                AiProvider.disabled("anthropic", breaker("anthropic"), "ANTHROPIC_API_KEY is not set"),
                AiProvider.enabled("acg-ollama", clientThrowing(new ResourceAccessException("acg down")), breaker("acg-ollama")),
                AiProvider.enabled("local-ollama", clientThrowing(new ResourceAccessException("local down")), breaker("local-ollama")));

        assertThatThrownBy(() -> chain.call("sys", "user"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("anthropic=disabled")
                .hasMessageContaining("acg-ollama=acg down")
                .hasMessageContaining("local-ollama=local down");
    }

    @Test
    void isAuthFailure_onlyMatches401And403() {
        assertThat(AiProviderChain.isAuthFailure(new NonTransientAiException("401 - bad key"))).isTrue();
        assertThat(AiProviderChain.isAuthFailure(new RuntimeException(new NonTransientAiException("403 - forbidden")))).isTrue();
        assertThat(AiProviderChain.isAuthFailure(new NonTransientAiException("429 - rate limited"))).isFalse();
        assertThat(AiProviderChain.isAuthFailure(new ResourceAccessException("timeout"))).isFalse();
    }

    private AiProviderChain chain(AiProvider... providers) {
        return new AiProviderChain(List.of(providers), AUTH_BLOCK, clock);
    }

    private CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(WAIT_OPEN)
                .permittedNumberOfCallsInHalfOpenState(1)
                .clock(clock)
                .build());
    }

    private static ChatClient clientReturning(String content) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(content);
        clearInvocations(client);
        return client;
    }

    private static ChatClient clientThrowing(RuntimeException ex) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenThrow(ex);
        clearInvocations(client);
        return client;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-26T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
