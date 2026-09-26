package me.sathish.runs_ai_analyzer.config;

import java.util.LinkedHashMap;
import java.util.Map;

import me.sathish.runs_ai_analyzer.service.ai.AiProvider;
import me.sathish.runs_ai_analyzer.service.ai.AiProviderChain;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows each failover tier's state under /actuator/health. Always UP: an open breaker on
 * one tier is expected (e.g. ACG sandbox torn down) and must not fail the container healthcheck.
 */
@Component("aiProviders")
public class AiProvidersHealthIndicator implements HealthIndicator {

    private final AiProviderChain chain;

    public AiProvidersHealthIndicator(AiProviderChain chain) {
        this.chain = chain;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (AiProvider provider : chain.providers()) {
            details.put(provider.name(), provider.enabled()
                    ? provider.breaker().getState().name()
                    : "DISABLED: " + provider.disabledReason());
        }
        return Health.up().withDetails(details).build();
    }
}
