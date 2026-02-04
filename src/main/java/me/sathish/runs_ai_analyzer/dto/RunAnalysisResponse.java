package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunAnalysisResponse {

    private boolean containsRunData;
    private String summary;
    private List<RunInsight> insights;
    private PerformanceMetrics metrics;
    private String rawAnalysis;
    private Instant analyzedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunInsight {
        private String category;
        private String observation;
        private String recommendation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics {
        private int totalRuns;
        private double totalDistanceKm;
        private String totalDuration;
        private Double averagePaceMinPerKm;
        private Integer averageHeartRate;
        private Integer totalCalories;
    }
}
