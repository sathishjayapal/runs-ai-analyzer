package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.AiStructuredAnalysis;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse.PerformanceMetrics;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse.RunInsight;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.exception.AiAnalysisException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunAnalysisServiceImpl implements RunAnalysisService {

    private static final String SYSTEM_PROMPT = """
            You are an expert running coach and sports analyst.
            Analyze the provided Garmin running data and return valid JSON only.

            Requirements:
            - Use this exact schema:
              {
                "summary": "string",
                "insights": [
                  {
                    "category": "string",
                    "observation": "string",
                    "recommendation": "string"
                  }
                ],
                "recommendations": ["string"],
                "riskFlags": ["string"],
                "confidenceScore": 0
              }
            - Return between 2 and 5 insights.
            - Return between 2 and 5 recommendations.
            - Use an empty array for riskFlags when there are no clear concerns.
            - confidenceScore must be an integer from 0 to 100.
            - Do not wrap the JSON in markdown code fences.
            - Base your analysis only on the supplied running data and derived metrics.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final RagStorageService ragStorageService;
    private final ObjectMapper objectMapper;
    private final RunAnalysisEventPublisher eventPublisher;

    @Override
    public RunAnalysisResponse analyzeRuns(List<GarminRunDataDTO> runs) {
        return analyzeRuns(runs, false);
    }

    @Override
    public RunAnalysisResponse analyzeRuns(List<GarminRunDataDTO> runs, boolean forceRefresh) {
        log.debug("Analyzing {} run(s), forceRefresh={}", runs.size(), forceRefresh);

        if (!containsRunData(runs)) {
            RunAnalysisResponse response = RunAnalysisResponse.builder()
                    .containsRunData(false)
                    .summary("No running activity data found in the provided dataset.")
                    .insights(List.of())
                    .recommendations(List.of())
                    .riskFlags(List.of())
                    .analyzedAt(Instant.now())
                    .build();
            eventPublisher.publishAnalysisCompleted(response, List.of());
            return response;
        }

        List<GarminRunDataDTO> runningActivities = runs.stream()
                .filter(run -> "running".equalsIgnoreCase(run.getActivityType()))
                .toList();

        String queryText = formatRunDataForAi(runningActivities);
        PerformanceMetrics metrics = calculateMetrics(runningActivities);

        if (!forceRefresh) {
            RunAnalysisResponse cachedResponse = tryGetCachedAnalysis(queryText, runningActivities);
            if (cachedResponse != null) {
                eventPublisher.publishAnalysisCompleted(cachedResponse, runningActivities);
                return cachedResponse;
            }
        }

        log.info("Generating fresh AI analysis for {} runs", runningActivities.size());
        String aiAnalysis = getAiAnalysis(runningActivities, metrics);
        AiStructuredAnalysis structuredAnalysis = toStructuredAnalysis(aiAnalysis, metrics, runningActivities);

        RunAnalysisResponse response = RunAnalysisResponse.builder()
                .containsRunData(true)
                .summary(structuredAnalysis.getSummary())
                .insights(defaultInsights(structuredAnalysis.getInsights()))
                .recommendations(defaultStrings(structuredAnalysis.getRecommendations()))
                .riskFlags(defaultStrings(structuredAnalysis.getRiskFlags()))
                .confidenceScore(normalizeConfidence(structuredAnalysis.getConfidenceScore()))
                .metrics(metrics)
                .rawAnalysis(aiAnalysis)
                .analyzedAt(Instant.now())
                .cachedResult(false)
                .build();

        RunAnalysisDocument savedDocument = storeAnalysisInRag(runningActivities, response, queryText);
        if (savedDocument != null) {
            response.setDocumentId(savedDocument.getDocumentId());
        }

        eventPublisher.publishAnalysisCompleted(response, runningActivities);
        return response;
    }

    private RunAnalysisResponse tryGetCachedAnalysis(String queryText, List<GarminRunDataDTO> runs) {
        try {
            var cachedDoc = ragStorageService.findCachedAnalysis(queryText);
            if (cachedDoc.isPresent()) {
                log.info("Using cached RAG analysis (documentId={})", cachedDoc.get().getDocumentId());
                return convertCachedDocumentToResponse(cachedDoc.get(), runs);
            }
        } catch (Exception e) {
            log.warn("Error checking RAG cache: {}. Proceeding with fresh analysis.", e.getMessage());
        }
        return null;
    }

    private RunAnalysisResponse convertCachedDocumentToResponse(RunAnalysisDocument cachedDoc, List<GarminRunDataDTO> runs) {
        Map<String, Object> metadata = cachedDoc.getMetadata() != null ? cachedDoc.getMetadata() : Map.of();

        PerformanceMetrics metrics = PerformanceMetrics.builder()
                .totalRuns(readInt(metadata, "totalRuns", cachedDoc.getTotalRuns() != null ? cachedDoc.getTotalRuns() : runs.size()))
                .totalDistanceKm(readDouble(metadata, "totalDistanceKm", cachedDoc.getTotalDistanceKm() != null ? cachedDoc.getTotalDistanceKm() : 0.0))
                .totalDuration(readString(metadata, "totalDuration"))
                .averagePaceMinPerKm(readNullableDouble(metadata, "averagePace"))
                .averageHeartRate(readNullableInt(metadata, "averageHeartRate"))
                .totalCalories(readNullableInt(metadata, "totalCalories"))
                .build();

        return RunAnalysisResponse.builder()
                .documentId(cachedDoc.getDocumentId())
                .containsRunData(true)
                .summary(readString(metadata, "structuredSummary", cachedDoc.getSummary()))
                .insights(readInsights(metadata.get("insights")))
                .recommendations(readStringList(metadata.get("recommendations")))
                .riskFlags(readStringList(metadata.get("riskFlags")))
                .confidenceScore(readNullableInt(metadata, "confidenceScore"))
                .metrics(metrics)
                .rawAnalysis(cachedDoc.getAnalysisContent())
                .analyzedAt(Instant.now())
                .cachedResult(true)
                .build();
    }

    private RunAnalysisDocument storeAnalysisInRag(List<GarminRunDataDTO> runs, RunAnalysisResponse response, String queryText) {
        try {
            RunAnalysisDocument saved = ragStorageService.storeAnalysis(runs, response, queryText);
            log.info("Stored analysis in RAG database for {} runs", runs.size());
            return saved;
        } catch (Exception e) {
            log.warn("Failed to store analysis in RAG database: {}. Continuing without storage.", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean containsRunData(List<GarminRunDataDTO> runs) {
        return runs.stream().anyMatch(run -> "running".equalsIgnoreCase(run.getActivityType()));
    }

    private String getAiAnalysis(List<GarminRunDataDTO> runs, PerformanceMetrics metrics) {
        String runDataSummary = formatRunDataForAi(runs);
        String metricSummary = formatMetricsForPrompt(metrics);

        try {
            ChatClient chatClient = chatClientBuilder.build();
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("""
                            Please analyze the following Garmin running data.

                            Derived metrics:
                            %s

                            Raw run details:
                            %s
                            """.formatted(metricSummary, runDataSummary))
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                throw new AiAnalysisException("AI analysis returned an empty response");
            }

            log.debug("Structured AI analysis received");
            return response;
        } catch (AiAnalysisException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiAnalysisException("Unable to generate run analysis from the AI provider", ex);
        }
    }

    private AiStructuredAnalysis toStructuredAnalysis(String aiAnalysis, PerformanceMetrics metrics, List<GarminRunDataDTO> runs) {
        try {
            return sanitizeStructuredAnalysis(parseStructuredAnalysis(aiAnalysis), metrics, runs);
        } catch (Exception ex) {
            log.warn("Failed to parse structured AI response. Falling back to deterministic insights: {}", ex.getMessage());
            return buildFallbackAnalysis(metrics, runs);
        }
    }

    private AiStructuredAnalysis parseStructuredAnalysis(String aiAnalysis) throws JsonProcessingException {
        String normalized = aiAnalysis.strip();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
        }

        try {
            return objectMapper.readValue(normalized, AiStructuredAnalysis.class);
        } catch (JsonProcessingException ex) {
            int firstBrace = normalized.indexOf('{');
            int lastBrace = normalized.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                String jsonOnly = normalized.substring(firstBrace, lastBrace + 1);
                return objectMapper.readValue(jsonOnly, AiStructuredAnalysis.class);
            }
            throw ex;
        }
    }

    private AiStructuredAnalysis sanitizeStructuredAnalysis(
            AiStructuredAnalysis analysis,
            PerformanceMetrics metrics,
            List<GarminRunDataDTO> runs) {

        List<RunInsight> insights = defaultInsights(analysis.getInsights());
        if (insights.isEmpty()) {
            insights = buildFallbackAnalysis(metrics, runs).getInsights();
        }

        String summary = analysis.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = generateSummary(metrics);
        }

        return AiStructuredAnalysis.builder()
                .summary(summary)
                .insights(insights)
                .recommendations(defaultStrings(analysis.getRecommendations()))
                .riskFlags(defaultStrings(analysis.getRiskFlags()))
                .confidenceScore(normalizeConfidence(analysis.getConfidenceScore()))
                .build();
    }

    private AiStructuredAnalysis buildFallbackAnalysis(PerformanceMetrics metrics, List<GarminRunDataDTO> runs) {
        List<RunInsight> insights = new ArrayList<>();
        insights.add(RunInsight.builder()
                .category("Volume")
                .observation("Analyzed %d running activities covering %.2f km.".formatted(
                        metrics.getTotalRuns(), metrics.getTotalDistanceKm()))
                .recommendation("Keep weekly volume steady before increasing distance or intensity.")
                .build());

        if (metrics.getAveragePaceMinPerKm() != null) {
            insights.add(RunInsight.builder()
                    .category("Pace")
                    .observation("Average pace across the analyzed block was %.2f min/km.".formatted(
                            metrics.getAveragePaceMinPerKm()))
                    .recommendation("Review your easiest sessions and keep them controlled enough to support recovery.")
                    .build());
        }

        if (metrics.getAverageHeartRate() != null) {
            insights.add(RunInsight.builder()
                    .category("Effort")
                    .observation("Average max heart rate across runs was %d bpm.".formatted(metrics.getAverageHeartRate()))
                    .recommendation("Compare heart-rate drift against pace to spot fatigue or heat impact.")
                    .build());
        }

        List<String> recommendations = new ArrayList<>();
        recommendations.add("Repeat a similar analysis after your next training block to compare load and recovery.");
        recommendations.add("Tag runs with workout intent so the model can better separate easy, tempo, and interval sessions.");

        List<String> riskFlags = new ArrayList<>();
        if (runs.size() >= 4) {
            riskFlags.add("Verify that your hardest sessions still have easy-day spacing around them.");
        }

        return AiStructuredAnalysis.builder()
                .summary(generateSummary(metrics))
                .insights(insights)
                .recommendations(recommendations)
                .riskFlags(riskFlags)
                .confidenceScore(60)
                .build();
    }

    private String formatRunDataForAi(List<GarminRunDataDTO> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Running Activities ===\n\n");

        for (int i = 0; i < runs.size(); i++) {
            GarminRunDataDTO run = runs.get(i);
            sb.append("Run #").append(i + 1).append(":\n");
            sb.append("  - Date: ").append(run.getActivityDate()).append('\n');
            sb.append("  - Name: ").append(run.getActivityName()).append('\n');
            sb.append("  - Distance: ").append(run.getDistance()).append(" km\n");
            sb.append("  - Duration: ").append(run.getElapsedTime()).append('\n');
            if (run.getMaxHeartRate() != null) {
                sb.append("  - Max Heart Rate: ").append(run.getMaxHeartRate()).append(" bpm\n");
            }
            if (run.getCalories() != null) {
                sb.append("  - Calories: ").append(run.getCalories()).append('\n');
            }
            if (run.getActivityDescription() != null && !run.getActivityDescription().isBlank()) {
                sb.append("  - Notes: ").append(run.getActivityDescription()).append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private String formatMetricsForPrompt(PerformanceMetrics metrics) {
        return """
                - Total runs: %d
                - Total distance: %.2f km
                - Total duration: %s
                - Average pace: %s min/km
                - Average max heart rate: %s bpm
                - Total calories: %s
                """.formatted(
                metrics.getTotalRuns(),
                metrics.getTotalDistanceKm(),
                metrics.getTotalDuration(),
                metrics.getAveragePaceMinPerKm() != null ? metrics.getAveragePaceMinPerKm() : "n/a",
                metrics.getAverageHeartRate() != null ? metrics.getAverageHeartRate() : "n/a",
                metrics.getTotalCalories() != null ? metrics.getTotalCalories() : "n/a");
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
                .mapToInt(d -> (int) Math.round(d))
                .findFirst()
                .orElse(0);

        int totalCalories = runs.stream()
                .filter(r -> r.getCalories() != null)
                .mapToInt(r -> parseInt(r.getCalories()))
                .sum();

        return PerformanceMetrics.builder()
                .totalRuns(runs.size())
                .totalDistanceKm(round(totalDistance))
                .totalDuration(formatSecondsToTime(totalSeconds))
                .averagePaceMinPerKm(avgPace != null ? round(avgPace) : null)
                .averageHeartRate(avgHr > 0 ? avgHr : null)
                .totalCalories(totalCalories > 0 ? totalCalories : null)
                .build();
    }

    private String generateSummary(PerformanceMetrics metrics) {
        return "Analysis of %d running activities covering %.2f km in %s. Average pace: %s min/km."
                .formatted(
                        metrics.getTotalRuns(),
                        metrics.getTotalDistanceKm(),
                        metrics.getTotalDuration(),
                        metrics.getAveragePaceMinPerKm() != null ? metrics.getAveragePaceMinPerKm() : "n/a");
    }

    private List<RunInsight> defaultInsights(List<RunInsight> insights) {
        return insights == null ? List.of() : insights.stream()
                .filter(insight -> insight.getCategory() != null || insight.getObservation() != null || insight.getRecommendation() != null)
                .toList();
    }

    private List<String> defaultStrings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private Integer normalizeConfidence(Integer confidenceScore) {
        if (confidenceScore == null) {
            return null;
        }
        return Math.max(0, Math.min(confidenceScore, 100));
    }

    private List<RunInsight> readInsights(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        return objectMapper.convertValue(
                rawValue,
                objectMapper.getTypeFactory().constructCollectionType(List.class, RunInsight.class));
    }

    private List<String> readStringList(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        return objectMapper.convertValue(
                rawValue,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private String readString(Map<String, Object> metadata, String key) {
        return readString(metadata, key, null);
    }

    private String readString(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private Integer readInt(Map<String, Object> metadata, String key, Integer defaultValue) {
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Integer readNullableInt(Map<String, Object> metadata, String key) {
        return readInt(metadata, key, null);
    }

    private Double readDouble(Map<String, Object> metadata, String key, Double defaultValue) {
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Double readNullableDouble(Map<String, Object> metadata, String key) {
        return readDouble(metadata, key, null);
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
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
