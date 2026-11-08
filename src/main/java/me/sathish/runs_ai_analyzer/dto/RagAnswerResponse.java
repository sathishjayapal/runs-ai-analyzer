package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Answer to a free-form question, written by the LLM from the runner's own stored analyses and journal entries.
 * Same field names the runs-app AI Insights page already renders: {@code answer}, {@code sources}, {@code error}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswerResponse {

    private String query;

    /** The coach's answer, plain text with "-" bullets and optional **bold** headings. Null when {@link #error} is set. */
    private String answer;

    /** Human-readable list of what the answer was based on, e.g. "Analysis of 2026-10-07 to 2026-10-14 (5 runs, 16.5 mi)". */
    private List<String> sources;

    /** Set when no answer could be produced (for example every AI provider is down). */
    private String error;
}
