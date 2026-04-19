package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStructuredAnalysis {

    private String summary;
    private List<RunAnalysisResponse.RunInsight> insights;
    private List<String> recommendations;
    private List<String> riskFlags;
    private Integer confidenceScore;
}
