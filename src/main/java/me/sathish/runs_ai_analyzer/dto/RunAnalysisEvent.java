package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunAnalysisEvent {

    private String eventType;
    private String sourceService;
    private UUID documentId;
    private boolean cachedResult;
    private boolean containsRunData;
    private int runCount;
    private List<String> activityIds;
    private String summary;
    private List<RunAnalysisResponse.RunInsight> insights;
    private List<String> recommendations;
    private List<String> riskFlags;
    private Integer confidenceScore;
    private RunAnalysisResponse.PerformanceMetrics metrics;
    private Instant analyzedAt;
}
