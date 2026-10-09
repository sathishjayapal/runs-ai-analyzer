package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.service.ai.AiProviderChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Cache behaviour for "Analyze My Runs": the same runs reuse the stored analysis,
 * while a new or edited run always produces a fresh LLM call.
 */
@ExtendWith(MockitoExtension.class)
class RunAnalysisServiceCacheTest {

    private static final String AI_JSON = """
            {"summary":"Fresh analysis",
             "insights":[{"category":"Volume","observation":"Steady week","recommendation":"Hold volume"}],
             "recommendations":["Easy day tomorrow","Long run Sunday"],
             "riskFlags":[],
             "confidenceScore":80}
            """;

    @Mock
    private AiProviderChain aiProviderChain;

    @Mock
    private RagStorageService ragStorageService;

    @Mock
    private RunAnalysisEventPublisher eventPublisher;

    private RunAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunAnalysisServiceImpl(aiProviderChain, ragStorageService, new ObjectMapper(), eventPublisher);
    }

    @Test
    void sameRunsAreServedFromCache_newRunTriggersFreshLlmAnalysis() {
        List<GarminRunDataDTO> lastWeek = List.of(
                run("A2", "2026-10-03", "8.0"),
                run("A1", "2026-10-01", "5.0"));
        List<GarminRunDataDTO> afterNewRun = List.of(
                run("A3", "2026-10-07", "10.0"),
                run("A2", "2026-10-03", "8.0"),
                run("A1", "2026-10-01", "5.0"));

        stubFreshAnalysisPath();

        // 1. Nothing cached yet -> LLM is called and the analysis is stored with its cache key.
        RunAnalysisResponse first = service.analyzeRuns(lastWeek, false);
        assertThat(first.isCachedResult()).isFalse();
        String lastWeekKey = lastStoredCacheKey(1);

        // 2. Same runs again -> served from cache, no LLM call.
        when(ragStorageService.findCachedAnalysis(lastWeekKey)).thenReturn(Optional.of(cachedDoc(lastWeekKey)));
        RunAnalysisResponse second = service.analyzeRuns(lastWeek, false);
        assertThat(second.isCachedResult()).isTrue();
        verify(aiProviderChain, times(1)).call(anyString(), anyString());

        // 3. A new run arrived -> different key -> cache miss -> fresh LLM analysis.
        RunAnalysisResponse third = service.analyzeRuns(afterNewRun, false);
        assertThat(third.isCachedResult()).isFalse();
        assertThat(third.getSummary()).isEqualTo("Fresh analysis");
        verify(aiProviderChain, times(2)).call(anyString(), anyString());
        assertThat(lastStoredCacheKey(2)).isNotEqualTo(lastWeekKey);
    }

    @Test
    void editedRunChangesCacheKey() {
        stubFreshAnalysisPath();

        service.analyzeRuns(List.of(run("A1", "2026-10-01", "5.0")), false);
        String originalKey = lastStoredCacheKey(1);

        service.analyzeRuns(List.of(run("A1", "2026-10-01", "5.5")), false);
        String editedKey = lastStoredCacheKey(2);

        assertThat(editedKey).isNotEqualTo(originalKey).hasSize(64);
    }

    @Test
    void forceRefreshSkipsCacheLookupAndCallsLlm() {
        when(aiProviderChain.call(anyString(), anyString())).thenReturn(AI_JSON);
        when(ragStorageService.storeAnalysis(anyList(), any(), anyString(), anyString()))
                .thenAnswer(inv -> RunAnalysisDocument.builder().documentId(UUID.randomUUID()).build());

        RunAnalysisResponse response = service.analyzeRuns(List.of(run("A1", "2026-10-01", "5.0")), true);

        assertThat(response.isCachedResult()).isFalse();
        verify(ragStorageService, never()).findCachedAnalysis(anyString());
        verify(aiProviderChain).call(anyString(), anyString());
    }

    @Test
    void cacheKeyIsDeterministicAndCoversSystemPrompt() {
        String key = RunAnalysisServiceImpl.buildCacheKey("system", "user");

        assertThat(key).isEqualTo(RunAnalysisServiceImpl.buildCacheKey("system", "user"));
        assertThat(key).isNotEqualTo(RunAnalysisServiceImpl.buildCacheKey("system v2", "user"));
        assertThat(key).isNotEqualTo(RunAnalysisServiceImpl.buildCacheKey("system", "user "));
    }

    private void stubFreshAnalysisPath() {
        when(ragStorageService.findCachedAnalysis(anyString())).thenReturn(Optional.empty());
        when(aiProviderChain.call(anyString(), anyString())).thenReturn(AI_JSON);
        when(ragStorageService.storeAnalysis(anyList(), any(), anyString(), anyString()))
                .thenAnswer(inv -> RunAnalysisDocument.builder()
                        .documentId(UUID.randomUUID())
                        .cacheKey(inv.getArgument(3))
                        .build());
    }

    private String lastStoredCacheKey(int expectedStoreCalls) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ragStorageService, times(expectedStoreCalls)).storeAnalysis(anyList(), any(), anyString(), key.capture());
        return key.getValue();
    }

    private static RunAnalysisDocument cachedDoc(String cacheKey) {
        return RunAnalysisDocument.builder()
                .documentId(UUID.randomUUID())
                .cacheKey(cacheKey)
                .summary("Cached analysis")
                .analysisContent(AI_JSON)
                .totalRuns(2)
                .totalDistanceMiles(8.08)
                .metadata(Map.of("structuredSummary", "Cached analysis"))
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private static GarminRunDataDTO run(String activityId, String date, String distanceKm) {
        return GarminRunDataDTO.builder()
                .activityId(activityId)
                .activityDate(date)
                .activityType("running")
                .activityName("Run " + activityId)
                .distance(distanceKm)
                .elapsedTime("00:45:00")
                .maxHeartRate("160")
                .calories("500")
                .build();
    }
}
