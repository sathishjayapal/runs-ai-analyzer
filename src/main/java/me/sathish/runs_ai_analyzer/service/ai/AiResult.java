package me.sathish.runs_ai_analyzer.service.ai;

import java.util.List;

/**
 * An answer plus which tier produced it and which earlier, enabled tiers were passed over and why.
 * Tiers that are switched off by configuration (no key, no URL) are not listed: they were never expected to answer.
 *
 * @param provider    name of the tier that answered, e.g. {@code openrouter} or {@code local-ollama}
 * @param unavailable enabled tiers ahead of {@code provider} that could not serve this call
 */
public record AiResult(String text, String provider, List<Skipped> unavailable) {

    /**
     * @param reason short, user-safe wording such as "daily limit reached"; never raw exception text
     */
    public record Skipped(String provider, String reason) {
    }
}
