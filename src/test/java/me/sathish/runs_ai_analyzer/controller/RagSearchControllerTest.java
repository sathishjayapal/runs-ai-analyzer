package me.sathish.runs_ai_analyzer.controller;

import me.sathish.runs_ai_analyzer.dto.RagSearchRequest;
import me.sathish.runs_ai_analyzer.dto.RagSearchResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.service.RagStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagSearchControllerTest {

    @Mock
    private RagStorageService ragStorageService;

    @InjectMocks
    private RagSearchController ragSearchController;

    private RunAnalysisDocument testDocument;
    private UUID testDocumentId;

    @BeforeEach
    void setUp() {
        testDocumentId = UUID.randomUUID();
        testDocument = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(testDocumentId)
                .activityIds("ACT001")
                .queryText("Test query")
                .analysisContent("Test content")
                .summary("Test summary")
                .totalRuns(1)
                .totalDistanceKm(5.0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void searchAnalyses_shouldReturnResults() {
        Document mockDoc = new Document("doc-123", "Test content", Map.of("totalRuns", 3));
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt()))
                .thenReturn(List.of(mockDoc));

        RagSearchRequest request = RagSearchRequest.builder()
                .query("morning run performance")
                .topK(5)
                .build();

        ResponseEntity<RagSearchResponse> response = ragSearchController.searchAnalyses(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getQuery()).isEqualTo("morning run performance");
        assertThat(response.getBody().getTotalResults()).isEqualTo(1);
        assertThat(response.getBody().getResults().get(0).getDocumentId()).isEqualTo("doc-123");
    }

    @Test
    void searchAnalyses_shouldReturnEmptyWhenNoResults() {
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt()))
                .thenReturn(List.of());

        RagSearchRequest request = RagSearchRequest.builder()
                .query("nonexistent query")
                .build();

        ResponseEntity<RagSearchResponse> response = ragSearchController.searchAnalyses(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalResults()).isEqualTo(0);
        assertThat(response.getBody().getResults()).isEmpty();
    }

    @Test
    void searchAnalyses_shouldUseDefaultTopK() {
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt()))
                .thenReturn(List.of());

        RagSearchRequest request = RagSearchRequest.builder()
                .query("test query")
                .topK(null)
                .build();

        ResponseEntity<RagSearchResponse> response = ragSearchController.searchAnalyses(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getRecentAnalyses_shouldReturnDocuments() {
        when(ragStorageService.getRecentAnalyses(10)).thenReturn(List.of(testDocument));

        ResponseEntity<List<RunAnalysisDocument>> response = ragSearchController.getRecentAnalyses(10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getActivityIds()).isEqualTo("ACT001");
    }

    @Test
    void getAnalysisByDocumentId_shouldReturnDocument() {
        when(ragStorageService.findByDocumentId(testDocumentId)).thenReturn(Optional.of(testDocument));

        ResponseEntity<RunAnalysisDocument> response = ragSearchController.getAnalysisByDocumentId(testDocumentId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getActivityIds()).isEqualTo("ACT001");
    }

    @Test
    void getAnalysisByDocumentId_shouldReturn404WhenNotFound() {
        UUID randomId = UUID.randomUUID();
        when(ragStorageService.findByDocumentId(randomId)).thenReturn(Optional.empty());

        ResponseEntity<RunAnalysisDocument> response = ragSearchController.getAnalysisByDocumentId(randomId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void findByActivityId_shouldReturnMatchingDocuments() {
        RunAnalysisDocument doc = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(UUID.randomUUID())
                .activityIds("ACT001,ACT002")
                .queryText("Test")
                .analysisContent("Content")
                .totalRuns(2)
                .createdAt(LocalDateTime.now())
                .build();

        when(ragStorageService.findAnalysesByActivityId("ACT001")).thenReturn(List.of(doc));

        ResponseEntity<List<RunAnalysisDocument>> response = ragSearchController.findByActivityId("ACT001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getActivityIds()).isEqualTo("ACT001,ACT002");
    }

    @Test
    void findByMinimumDistance_shouldReturnMatchingDocuments() {
        RunAnalysisDocument doc = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(UUID.randomUUID())
                .activityIds("ACT001")
                .queryText("Test")
                .analysisContent("Content")
                .totalRuns(1)
                .totalDistanceKm(15.0)
                .createdAt(LocalDateTime.now())
                .build();

        when(ragStorageService.findAnalysesByMinimumDistance(10.0)).thenReturn(List.of(doc));

        ResponseEntity<List<RunAnalysisDocument>> response = ragSearchController.findByMinimumDistance(10.0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getTotalDistanceKm()).isEqualTo(15.0);
    }
}
