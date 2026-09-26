package me.sathish.runs_ai_analyzer.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigKeyTest {

    @Test
    void anthropicKeyProblem_flagsMissingAndPlaceholderKeys() {
        assertThat(AiConfig.anthropicKeyProblem(null)).contains("not set");
        assertThat(AiConfig.anthropicKeyProblem("  ")).contains("not set");
        assertThat(AiConfig.anthropicKeyProblem("${ANTHROPIC_API_KEY}")).contains("does not look like");
        assertThat(AiConfig.anthropicKeyProblem("sk-ant-changeme")).contains("does not look like");
        assertThat(AiConfig.anthropicKeyProblem("<your-key>")).contains("does not look like");
        assertThat(AiConfig.anthropicKeyProblem("sk-ant-api03-abc123")).isNull();
    }

    @Test
    void resolveAnthropicKey_unresolvedEnvPlaceholder_isTreatedAsMissing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.anthropic.api-key", "${ANTHROPIC_API_KEY}");

        String key = AiConfig.resolveAnthropicKey(env);

        assertThat(AiConfig.anthropicKeyProblem(key)).isNotNull();
    }
}
