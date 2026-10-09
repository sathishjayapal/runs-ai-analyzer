package me.sathish.runs_ai_analyzer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.cache")
public class RagCacheProperties {

    /**
     * Time-to-live in days for cached analyses.
     * Analyses older than this are considered stale.
     */
    private int ttlDays = 7;

    /**
     * Whether the run-analysis cache is enabled. Cache hits require an exact match on
     * the LLM input (see RunAnalysisDocument.cacheKey); there is no similarity matching.
     */
    private boolean enabled = true;
}
