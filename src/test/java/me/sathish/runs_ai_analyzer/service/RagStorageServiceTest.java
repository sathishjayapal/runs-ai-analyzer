package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.sathish.runs_ai_analyzer.config.RagCacheProperties;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse.PerformanceMetrics;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.repository.RunAnalysisDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagStorageServiceTest {

    private static final String CACHE_KEY = "a".repeat(64);

    @Mock
    private RunAnalysisDocumentRepository documentRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private RagCacheProperties cacheProperties;

    private RagStorageServiceImpl ragStorageService;

    @Captor
    private ArgumentCaptor<RunAnalysisDocument> documentCaptor;

    @Captor
    private ArgumentCaptor<List<Document>> vectorDocumentCaptor;

    private List<GarminRunDataDTO> testRuns;
    private RunAnalysisResponse testResponse;

    @BeforeEach
    void setUp() {
        ragStorageService = new RagStorageServiceImpl(documentRepository, vectorStore, cacheProperties, new ObjectMapper());
        
        testRuns = List.of(
                GarminRunDataDTO.builder()
                        .activityId("ACT001")
                        .activityDate("2024-01-15")
                        .activityType("running")
                        .activityName("Morning Run")
                        .distance("5.0")
                        .elapsedTime("00:30:00")
                        .maxHeartRate("165")
                        .calories("350")
                        .build(),
                GarminRunDataDTO.builder()
                        .activityId("ACT002")
                        .activityDate("2024-01-17")
                        .activityType("running")
                        .activityName("Evening Run")
                        .distance("7.5")
                        .elapsedTime("00:45:00")
                        .maxHeartRate("170")
                        .calories("500")
                        .build()
        );

        testResponse = RunAnalysisResponse.builder()
                .containsRunData(true)
                .summary("Analysis of 2 runs covering 12.5 km")
                .rawAnalysis("Detailed AI analysis of running performance...")
                .recommendations(List.of("Recover well", "Keep easy runs easy"))
                .riskFlags(List.of("Watch fatigue"))
                .confidenceScore(84)
                .metrics(PerformanceMetrics.builder()
                        .totalRuns(2)
                        .totalDistanceMiles(12.5)
                        .totalDuration("01:15:00")
                        .averagePaceMinPerMile(6.0)
                        .averageHeartRate(167)
                        .totalCalories(850)
                        .build())
                .analyzedAt(Instant.now())
                .build();
    }

    @Test
    void storeAnalysis_shouldSaveDocumentAndStoreInVectorStore() {
        RunAnalysisDocument savedDocument = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(UUID.randomUUID())
                .activityIds("ACT001,ACT002")
                .queryText("test query")
                .analysisContent(testResponse.getRawAnalysis())
                .summary(testResponse.getSummary())
                .totalRuns(2)
                .totalDistanceMiles(12.5)
                .createdAt(LocalDateTime.now())
                .build();

        when(documentRepository.save(any(RunAnalysisDocument.class))).thenReturn(savedDocument);
        doNothing().when(vectorStore).add(anyList());

        RunAnalysisDocument result = ragStorageService.storeAnalysis(testRuns, testResponse, "test query", CACHE_KEY);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);

        verify(documentRepository).save(documentCaptor.capture());
        RunAnalysisDocument capturedDoc = documentCaptor.getValue();
        assertThat(capturedDoc.getCacheKey()).isEqualTo(CACHE_KEY);
        assertThat(capturedDoc.getCacheEvictedAt()).isNull();
        assertThat(capturedDoc.getActivityIds()).isEqualTo("ACT001,ACT002");
        assertThat(capturedDoc.getTotalRuns()).isEqualTo(2);
        assertThat(capturedDoc.getTotalDistanceMiles()).isEqualTo(12.5);
        assertThat(capturedDoc.getMetadata()).containsEntry("structuredSummary", "Analysis of 2 runs covering 12.5 km");
        assertThat(capturedDoc.getMetadata()).containsEntry("confidenceScore", 84);
        assertThat(capturedDoc.getMetadata()).containsKey("insights");
        assertThat(capturedDoc.getMetadata()).containsKey("recommendations");

        verify(vectorStore).add(vectorDocumentCaptor.capture());
        List<Document> vectorDocs = vectorDocumentCaptor.getValue();
        assertThat(vectorDocs).hasSize(1);
        assertThat(vectorDocs.getFirst().getText()).contains("Summary: Analysis of 2 runs covering 12.5 km");
    }

    @Test
    void storeAnalysis_shouldContinueWhenVectorStoreFails() {
        UUID docId = UUID.randomUUID();
        RunAnalysisDocument savedDocument = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(docId)
                .activityIds("ACT001,ACT002")
                .queryText("test query")
                .analysisContent(testResponse.getRawAnalysis())
                .summary(testResponse.getSummary())
                .totalRuns(2)
                .totalDistanceMiles(12.5)
                .createdAt(LocalDateTime.now())
                .build();

        when(documentRepository.save(any(RunAnalysisDocument.class))).thenReturn(savedDocument);
        doThrow(new RuntimeException("Vector store error")).when(vectorStore).add(anyList());

        RunAnalysisDocument result = ragStorageService.storeAnalysis(testRuns, testResponse, "test query", null);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        verify(documentRepository).save(any(RunAnalysisDocument.class));
        verify(vectorStore).add(anyList());
    }

    @Test
    void searchSimilarAnalyses_shouldReturnDocuments() {
        Document mockDoc = new Document("doc-id", "content", Map.of("key", "value"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(mockDoc));

        List<Document> results = ragStorageService.searchSimilarAnalyses("test query", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("doc-id");
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void searchSimilarAnalyses_shouldReturnEmptyListOnError() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Search error"));

        List<Document> results = ragStorageService.searchSimilarAnalyses("test query", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void findByDocumentId_shouldReturnDocument() {
        UUID documentId = UUID.randomUUID();
        RunAnalysisDocument document = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(documentId)
                .build();

        when(documentRepository.findByDocumentId(documentId)).thenReturn(Optional.of(document));

        Optional<RunAnalysisDocument> result = ragStorageService.findByDocumentId(documentId);

        assertThat(result).isPresent();
        assertThat(result.get().getDocumentId()).isEqualTo(documentId);
    }

    @Test
    void getRecentAnalyses_shouldReturnTop10() {
        List<RunAnalysisDocument> recentDocs = List.of(
                RunAnalysisDocument.builder().id(1L).build(),
                RunAnalysisDocument.builder().id(2L).build()
        );

        when(documentRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(recentDocs);

        List<RunAnalysisDocument> results = ragStorageService.getRecentAnalyses(10);

        assertThat(results).hasSize(2);
        verify(documentRepository).findTop10ByOrderByCreatedAtDesc();
    }

    @Test
    void findAnalysesByActivityId_shouldReturnMatchingDocuments() {
        List<RunAnalysisDocument> matchingDocs = List.of(
                RunAnalysisDocument.builder().id(1L).activityIds("ACT001,ACT002").build()
        );

        when(documentRepository.findByActivityIdContaining("ACT001")).thenReturn(matchingDocs);

        List<RunAnalysisDocument> results = ragStorageService.findAnalysesByActivityId("ACT001");

        assertThat(results).hasSize(1);
        verify(documentRepository).findByActivityIdContaining("ACT001");
    }

    @Test
    void findAnalysesByMinimumDistance_shouldReturnMatchingDocuments() {
        List<RunAnalysisDocument> matchingDocs = List.of(
                RunAnalysisDocument.builder().id(1L).totalDistanceMiles(15.0).build()
        );

        when(documentRepository.findByMinimumDistance(10.0)).thenReturn(matchingDocs);

        List<RunAnalysisDocument> results = ragStorageService.findAnalysesByMinimumDistance(10.0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTotalDistanceMiles()).isEqualTo(15.0);
    }

    @Test
    void findCachedAnalysis_shouldReturnEmptyWhenCacheDisabled() {
        when(cacheProperties.isEnabled()).thenReturn(false);

        Optional<RunAnalysisDocument> result = ragStorageService.findCachedAnalysis(CACHE_KEY);

        assertThat(result).isEmpty();
        verifyNoInteractions(documentRepository, vectorStore);
    }

    @Test
    void findCachedAnalysis_shouldReturnEmptyWhenCacheKeyMissing() {
        when(cacheProperties.isEnabled()).thenReturn(true);

        assertThat(ragStorageService.findCachedAnalysis(null)).isEmpty();
        assertThat(ragStorageService.findCachedAnalysis(" ")).isEmpty();
        verifyNoInteractions(documentRepository, vectorStore);
    }

    @Test
    void findCachedAnalysis_shouldReturnDocumentForExactKeyWithinTtl() {
        UUID documentId = UUID.randomUUID();
        when(cacheProperties.isEnabled()).thenReturn(true);
        when(cacheProperties.getTtlDays()).thenReturn(7);

        RunAnalysisDocument cachedDoc = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(documentId)
                .cacheKey(CACHE_KEY)
                .summary("Cached summary")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
        when(documentRepository.findFirstByCacheKeyAndCacheEvictedAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CACHE_KEY), any(LocalDateTime.class))).thenReturn(Optional.of(cachedDoc));

        Optional<RunAnalysisDocument> result = ragStorageService.findCachedAnalysis(CACHE_KEY);

        assertThat(result).isPresent();
        assertThat(result.get().getDocumentId()).isEqualTo(documentId);

        ArgumentCaptor<LocalDateTime> ttlCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).findFirstByCacheKeyAndCacheEvictedAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CACHE_KEY), ttlCutoff.capture());
        LocalDateTime expectedCutoff = LocalDateTime.now().minusDays(7);
        assertThat(ttlCutoff.getValue()).isBetween(expectedCutoff.minusMinutes(1), expectedCutoff.plusMinutes(1));

        // Cache lookup is an exact key match; it must not depend on vector similarity.
        verifyNoInteractions(vectorStore);
    }

    @Test
    void findCachedAnalysis_shouldReturnEmptyWhenNoAnalysisForKey() {
        when(cacheProperties.isEnabled()).thenReturn(true);
        when(cacheProperties.getTtlDays()).thenReturn(7);
        when(documentRepository.findFirstByCacheKeyAndCacheEvictedAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
                anyString(), any(LocalDateTime.class))).thenReturn(Optional.empty());

        Optional<RunAnalysisDocument> result = ragStorageService.findCachedAnalysis(CACHE_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    void findCachedAnalysis_shouldReturnEmptyOnRepositoryError() {
        when(cacheProperties.isEnabled()).thenReturn(true);
        when(cacheProperties.getTtlDays()).thenReturn(7);
        when(documentRepository.findFirstByCacheKeyAndCacheEvictedAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
                anyString(), any(LocalDateTime.class))).thenThrow(new RuntimeException("Database error"));

        Optional<RunAnalysisDocument> result = ragStorageService.findCachedAnalysis(CACHE_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    void evictCachedAnalyses_shouldEvictAllActiveCacheEntries() {
        when(documentRepository.evictAllCachedAnalyses(any(LocalDateTime.class))).thenReturn(3);

        int evicted = ragStorageService.evictCachedAnalyses();

        assertThat(evicted).isEqualTo(3);
        verify(documentRepository).evictAllCachedAnalyses(any(LocalDateTime.class));
        verifyNoInteractions(vectorStore);
    }
}
