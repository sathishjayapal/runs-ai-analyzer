package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse.PerformanceMetrics;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse.RunInsight;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunAnalysisServiceImpl implements RunAnalysisService {

    private final ChatClient.Builder chatClientBuilder;

    private static final String SYSTEM_PROMPT = """
            You are an expert running coach and sports analyst. Analyze the provided Garmin running data 
            and provide actionable insights. Focus on:
            1. Overall performance assessment
            2. Pace analysis and trends
            3. Heart rate zone observations (if available)
            4. Recovery and training load recommendations
            5. Areas for improvement
            
            Be specific, encouraging, and data-driven in your analysis.
            Format your response with clear sections for Summary, Key Insights, and Recommendations.
            """;

    @Override
    public RunAnalysisResponse analyzeRuns(List<GarminRunDataDTO> runs) {
        log.debug("Analyzing {} run(s)", runs.size());

        boolean hasRunData = containsRunData(runs);
        if (!hasRunData) {
            return RunAnalysisResponse.builder()
                    .containsRunData(false)
                    .summary("No running activity data found in the provided dataset.")
                    .insights(List.of())
                    .analyzedAt(Instant.now())
                    .build();
        }

        List<GarminRunDataDTO> runningActivities = runs.stream()
                .filter(run -> "running".equalsIgnoreCase(run.getActivityType()))
                .toList();

        PerformanceMetrics metrics = calculateMetrics(runningActivities);
        String aiAnalysis = getAiAnalysis(runningActivities);
        List<RunInsight> insights = parseInsights(aiAnalysis, runningActivities);

        return RunAnalysisResponse.builder()
                .containsRunData(true)
                .summary(generateSummary(runningActivities, metrics))
                .insights(insights)
                .metrics(metrics)
                .rawAnalysis(aiAnalysis)
                .analyzedAt(Instant.now())
                .build();
    }

    @Override
    public boolean containsRunData(List<GarminRunDataDTO> runs) {
        return runs.stream()
                .anyMatch(run -> "running".equalsIgnoreCase(run.getActivityType()));
    }

    private String getAiAnalysis(List<GarminRunDataDTO> runs) {
        String runDataSummary = formatRunDataForAi(runs);

        ChatClient chatClient = chatClientBuilder.build();

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Please analyze the following Garmin running data:\n\n" + runDataSummary)
                .call()
                .content();

        log.debug("OpenAI analysis response received");
        return response;
    }

    private String formatRunDataForAi(List<GarminRunDataDTO> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Running Activities ===\n\n");

        for (int i = 0; i < runs.size(); i++) {
            GarminRunDataDTO run = runs.get(i);
            sb.append(String.format("Run #%d:\n", i + 1));
            sb.append(String.format("  - Date: %s\n", run.getActivityDate()));
            sb.append(String.format("  - Name: %s\n", run.getActivityName()));
            sb.append(String.format("  - Distance: %s km\n", run.getDistance()));
            sb.append(String.format("  - Duration: %s\n", run.getElapsedTime()));
            if (run.getMaxHeartRate() != null) {
                sb.append(String.format("  - Max Heart Rate: %s bpm\n", run.getMaxHeartRate()));
            }
            if (run.getCalories() != null) {
                sb.append(String.format("  - Calories: %s\n", run.getCalories()));
            }
            if (run.getActivityDescription() != null && !run.getActivityDescription().isBlank()) {
                sb.append(String.format("  - Notes: %s\n", run.getActivityDescription()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private PerformanceMetrics calculateMetrics(List<GarminRunDataDTO> runs) {
        double totalDistance = runs.stream()
                .mapToDouble(r -> parseDouble(r.getDistance()))
                .sum();

        long totalSeconds = runs.stream()
                .mapToLong(r -> parseTimeToSeconds(r.getElapsedTime()))
                .sum();

        Double avgPace = totalDistance > 0 ? (totalSeconds / 60.0) / totalDistance : null;

        Integer avgHr = runs.stream()
                .filter(r -> r.getMaxHeartRate() != null)
                .mapToInt(r -> parseInt(r.getMaxHeartRate()))
                .average()
                .stream()
                .mapToInt(d -> (int) d)
                .findFirst()
                .orElse(0);

        int totalCalories = runs.stream()
                .filter(r -> r.getCalories() != null)
                .mapToInt(r -> parseInt(r.getCalories()))
                .sum();

        return PerformanceMetrics.builder()
                .totalRuns(runs.size())
                .totalDistanceKm(Math.round(totalDistance * 100.0) / 100.0)
                .totalDuration(formatSecondsToTime(totalSeconds))
                .averagePaceMinPerKm(avgPace != null ? Math.round(avgPace * 100.0) / 100.0 : null)
                .averageHeartRate(avgHr > 0 ? avgHr : null)
                .totalCalories(totalCalories > 0 ? totalCalories : null)
                .build();
    }

    private List<RunInsight> parseInsights(String aiAnalysis, List<GarminRunDataDTO> runs) {
        List<RunInsight> insights = new ArrayList<>();

        insights.add(RunInsight.builder()
                .category("Volume")
                .observation(String.format("Analyzed %d running activities", runs.size()))
                .recommendation("Consistent training is key to improvement")
                .build());

        if (runs.size() >= 3) {
            insights.add(RunInsight.builder()
                    .category("Consistency")
                    .observation("Good training frequency detected")
                    .recommendation("Maintain this consistency while varying intensity")
                    .build());
        }

        return insights;
    }

    private String generateSummary(List<GarminRunDataDTO> runs, PerformanceMetrics metrics) {
        return String.format(
                "Analysis of %d running activities covering %.2f km in %s. " +
                "Average pace: %.2f min/km.",
                metrics.getTotalRuns(),
                metrics.getTotalDistanceKm(),
                metrics.getTotalDuration(),
                metrics.getAveragePaceMinPerKm() != null ? metrics.getAveragePaceMinPerKm() : 0.0
        );
    }

    private double parseDouble(String value) {
        try {
            return value != null ? Double.parseDouble(value) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseInt(String value) {
        try {
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseTimeToSeconds(String time) {
        if (time == null || !time.matches("\\d{2}:\\d{2}:\\d{2}")) {
            return 0;
        }
        String[] parts = time.split(":");
        return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
    }

    private String formatSecondsToTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
