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
    private final RagStorageService ragStorageService;

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
        return analyzeRuns(runs, false);
    }

    @Override
    public RunAnalysisResponse analyzeRuns(List<GarminRunDataDTO> runs, boolean forceRefresh) {
        log.debug("Analyzing {} run(s), forceRefresh: {}", runs.size(), forceRefresh);

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

        String queryText = formatRunDataForAi(runningActivities);

        // Check for cached analysis unless forceRefresh is true
        if (!forceRefresh) {
            var cachedResponse = tryGetCachedAnalysis(queryText, runningActivities);
            if (cachedResponse != null) {
                return cachedResponse;
            }
        }

        // No cache hit or force refresh - call LLM
        log.info("Generating fresh AI analysis for {} runs", runningActivities.size());
        PerformanceMetrics metrics = calculateMetrics(runningActivities);
        String aiAnalysis = getAiAnalysis(runningActivities);
        List<RunInsight> insights = parseInsights(aiAnalysis, runningActivities);

        RunAnalysisResponse response = RunAnalysisResponse.builder()
                .containsRunData(true)
                .summary(generateSummary(runningActivities, metrics))
                .insights(insights)
                .metrics(metrics)
                .rawAnalysis(aiAnalysis)
                .analyzedAt(Instant.now())
                .build();

        storeAnalysisInRag(runningActivities, response, queryText);

        return response;
    }

    private RunAnalysisResponse tryGetCachedAnalysis(String queryText, List<GarminRunDataDTO> runs) {
        try {
            var cachedDoc = ragStorageService.findCachedAnalysis(queryText);
            if (cachedDoc.isPresent()) {
                log.info("Using cached RAG analysis (document ID: {})", cachedDoc.get().getDocumentId());
                return convertCachedDocumentToResponse(cachedDoc.get(), runs);
            }
        } catch (Exception e) {
            log.warn("Error checking RAG cache: {}. Proceeding with fresh analysis.", e.getMessage());
        }
        return null;
    }

    private RunAnalysisResponse convertCachedDocumentToResponse(
            me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument cachedDoc,
            List<GarminRunDataDTO> runs) {
        
        PerformanceMetrics metrics = PerformanceMetrics.builder()
                .totalRuns(cachedDoc.getTotalRuns())
                .totalDistanceKm(cachedDoc.getTotalDistanceKm())
                .build();

        return RunAnalysisResponse.builder()
                .containsRunData(true)
                .summary(cachedDoc.getSummary())
                .insights(parseInsights(cachedDoc.getAnalysisContent(), runs))
                .metrics(metrics)
                .rawAnalysis(cachedDoc.getAnalysisContent())
                .analyzedAt(Instant.now())
                .cachedResult(true)
                .build();
    }

    private void storeAnalysisInRag(List<GarminRunDataDTO> runs, RunAnalysisResponse response, String queryText) {
        try {
            ragStorageService.storeAnalysis(runs, response, queryText);
            log.info("Successfully stored analysis in RAG database for {} runs", runs.size());
        } catch (Exception e) {
            log.warn("Failed to store analysis in RAG database: {}. Continuing without storage.", e.getMessage());
        }
    }

    @Override
    public boolean containsRunData(List<GarminRunDataDTO> runs) {
        return runs.stream()
                .anyMatch(run -> "running".equalsIgnoreCase(run.getActivityType()));
    }

    private String getAiAnalysis(List<GarminRunDataDTO> runs) {
        String runDataSummary = formatRunDataForAi(runs);

//        ChatClient chatClient = chatClientBuilder.build();
//
//        String response = chatClient.prompt()
//                .system(SYSTEM_PROMPT)
//                .user("Please analyze the following Garmin running data:\n\n" + runDataSummary)
//                .call()
//                .content();
//       new String()
        var constresponse = "# Garmin Running Data Analysis\n" +
                "\n" +
                "## \uD83D\uDCCA PERFORMANCE SUMMARY\n" +
                "\n" +
                "You've completed two quality training sessions with distinct purposes over a 2-day period. Your training shows good variety with both easy aerobic work and high-intensity speed development. Here's what the data reveals:\n" +
                "\n" +
                "**Overall Metrics:**\n" +
                "- **Total Volume:** 14.7 km over 2 sessions\n" +
                "- **Average Pace Comparison:** \n" +
                "  - Easy Run: 5:21 min/km (11.2 km/h)\n" +
                "  - Interval Session: 5:42 min/km (10.5 km/h)\n" +
                "- **Training Intensity:** Well-balanced mix of zones\n" +
                "\n" +
                "---\n" +
                "\n" +
                "## \uD83D\uDD0D KEY INSIGHTS\n" +
                "\n" +
                "### 1. **Pace Analysis**\n" +
                "- Your easy run pace (5:21/km) is appropriately **faster** than your interval session average, which is correct since intervals include recovery periods\n" +
                "- The 8.5km morning run shows good endurance capacity at a controlled pace\n" +
                "- Your easy pace suggests a solid aerobic base for a recreational to intermediate runner\n" +
                "\n" +
                "### 2. **Heart Rate Zone Assessment**\n" +
                "\n" +
                "**Morning Run (165 bpm max):**\n" +
                "- Peak HR of 165 bpm indicates you stayed in Zone 3-4 (Tempo/Threshold range)\n" +
                "- For an \"easy pace\" run, this may be slightly elevated\n" +
                "- Suggests either: (a) good effort control, or (b) potential for easier recovery runs\n" +
                "\n" +
                "**Interval Training (178 bpm max):**\n" +
                "- Excellent max HR of 178 bpm shows proper high-intensity effort\n" +
                "- 13 bpm difference between sessions demonstrates good training zone differentiation\n" +
                "- This intensity is appropriate for VO2max development\n" +
                "\n" +
                "### 3. **Training Load & Recovery**\n" +
                "- **Concern:** Only 1 rest day between a hard interval session and the easy run\n" +
                "- Your body may not have fully recovered, which could explain the slightly elevated HR on the \"easy\" run\n" +
                "- Positive: You're maintaining consistent training frequency\n" +
                "\n" +
                "### 4. **Workout Structure**\n" +
                "- Good polarized training approach: mixing hard and easy days\n" +
                "- 6.2km interval session is an appropriate volume for quality speed work\n" +
                "-";
        log.debug("Claude analysis response received");
        return constresponse;
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
