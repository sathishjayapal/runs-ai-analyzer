package me.sathish.runs_ai_analyzer.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import me.sathish.runs_ai_analyzer.service.ai.AiProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigOpenRouterTest {

    private final CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.ofDefaults();

    @Test
    void defaultsAreCheapAndBounded() {
        AiFallbackProperties.OpenRouter defaults = new AiFallbackProperties().getOpenrouter();

        assertThat(defaults.getApiKey()).isEmpty();
        assertThat(defaults.getBaseUrl()).isEqualTo("https://openrouter.ai/api");
        assertThat(defaults.getMaxTokens()).isEqualTo(1200);
        assertThat(defaults.getDailyCallCap()).isEqualTo(200);
        assertThat(defaults.isDenyDataCollection()).isTrue();
    }

    @Test
    void noKeyMeansTheTierIsOffAndStartupIsUnaffected() {
        AiProvider provider = AiConfig.buildOpenRouter(new AiFallbackProperties.OpenRouter(), breakerConfig);

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.name()).isEqualTo("openrouter");
        assertThat(provider.disabledReason()).isEqualTo("OPENROUTER_API_KEY is not set");
    }

    @Test
    void anUnresolvedPlaceholderIsTreatedAsNoKey() {
        AiFallbackProperties.OpenRouter props = new AiFallbackProperties.OpenRouter();
        props.setApiKey("${OPENROUTER_API_KEY}");

        AiProvider provider = AiConfig.buildOpenRouter(props, breakerConfig);

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.disabledReason()).contains("placeholder");
    }

    @Test
    void aKeyEnablesTheTierWithTheConfiguredDailyCap() {
        AiFallbackProperties.OpenRouter props = new AiFallbackProperties.OpenRouter();
        props.setApiKey("sk-or-v1-test");
        props.setDailyCallCap(7);

        AiProvider provider = AiConfig.buildOpenRouter(props, breakerConfig);

        assertThat(provider.enabled()).isTrue();
        assertThat(provider.client()).isNotNull();
        assertThat(provider.dailyCallLimit()).isEqualTo(7);
    }
}
