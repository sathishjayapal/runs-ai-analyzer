package me.sathish.runs_ai_analyzer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.cache")
public class RagCacheProperties {

    /**
     * Similarity threshold (0.0 to 1.0) for considering a cached analysis as a match.
     * Higher values require more similar matches.
     */
    private double similarityThreshold = 0.85;

    /**
     * Time-to-live in days for cached analyses.
     * Analyses older than this are considered stale.
     */
    private int ttlDays = 7;

    /**
     * Whether RAG caching is enabled.
     */
    private boolean enabled = true;
}
