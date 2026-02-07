package me.sathish.runs_ai_analyzer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunAnalysisRequest {

    @NotEmpty(message = "At least one run data entry is required")
    @Valid
    private List<GarminRunDataDTO> runs;

    /**
     * When true, bypasses RAG cache and forces a fresh LLM analysis.
     * Default is false (use cache if available).
     */
    @Builder.Default
    private Boolean forceRefresh = false;

    public boolean isForceRefresh() {
        return forceRefresh != null && forceRefresh;
    }
}
