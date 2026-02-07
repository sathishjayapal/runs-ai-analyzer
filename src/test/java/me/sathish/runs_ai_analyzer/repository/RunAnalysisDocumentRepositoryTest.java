package me.sathish.runs_ai_analyzer.repository;

import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunAnalysisDocumentRepositoryTest {

    @Mock
    private RunAnalysisDocumentRepository repository;

    private RunAnalysisDocument testDocument;
    private UUID testDocumentId;

    @BeforeEach
    void setUp() {
        testDocumentId = UUID.randomUUID();
        testDocument = RunAnalysisDocument.builder()
                .id(1L)
                .documentId(testDocumentId)
                .activityIds("ACT001,ACT002,ACT003")
                .queryText("Morning run analysis")
                .analysisContent("Good pace maintained throughout the run")
                .summary("Analysis of 3 runs covering 15 km")
                .totalRuns(3)
                .totalDistanceKm(15.0)
                .metadata(Map.of("avgPace", 5.5))
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void shouldSaveAndFindDocument() {
        when(repository.save(any(RunAnalysisDocument.class))).thenReturn(testDocument);

        RunAnalysisDocument saved = repository.save(testDocument);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDocumentId()).isEqualTo(testDocumentId);
    }

    @Test
    void shouldFindByDocumentId() {
        when(repository.findByDocumentId(testDocumentId)).thenReturn(Optional.of(testDocument));

        Optional<RunAnalysisDocument> found = repository.findByDocumentId(testDocumentId);

        assertThat(found).isPresent();
        assertThat(found.get().getActivityIds()).isEqualTo("ACT001,ACT002,ACT003");
    }

    @Test
    void shouldReturnEmptyWhenDocumentIdNotFound() {
        UUID randomId = UUID.randomUUID();
        when(repository.findByDocumentId(randomId)).thenReturn(Optional.empty());

        Optional<RunAnalysisDocument> found = repository.findByDocumentId(randomId);

        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindTop10ByOrderByCreatedAtDesc() {
        List<RunAnalysisDocument> recentDocs = List.of(
                createDocument(1L, "ACT1", LocalDateTime.now()),
                createDocument(2L, "ACT2", LocalDateTime.now().minusHours(1))
        );

        when(repository.findTop10ByOrderByCreatedAtDesc()).thenReturn(recentDocs);

        List<RunAnalysisDocument> recent = repository.findTop10ByOrderByCreatedAtDesc();

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getCreatedAt()).isAfter(recent.get(1).getCreatedAt());
    }

    @Test
    void shouldFindByActivityIdContaining() {
        when(repository.findByActivityIdContaining("ACT002")).thenReturn(List.of(testDocument));

        List<RunAnalysisDocument> found = repository.findByActivityIdContaining("ACT002");

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getActivityIds()).contains("ACT002");
    }

    @Test
    void shouldFindByMinimumDistance() {
        RunAnalysisDocument longRun = RunAnalysisDocument.builder()
                .id(2L)
                .documentId(UUID.randomUUID())
                .activityIds("LONG1")
                .queryText("Long run")
                .analysisContent("Long distance run")
                .totalRuns(1)
                .totalDistanceKm(20.0)
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.findByMinimumDistance(10.0)).thenReturn(List.of(longRun));

        List<RunAnalysisDocument> found = repository.findByMinimumDistance(10.0);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTotalDistanceKm()).isEqualTo(20.0);
    }

    @Test
    void shouldFindByMinimumRuns() {
        RunAnalysisDocument manyRuns = RunAnalysisDocument.builder()
                .id(2L)
                .documentId(UUID.randomUUID())
                .activityIds("MANY1,MANY2,MANY3,MANY4,MANY5")
                .queryText("Many runs")
                .analysisContent("Analysis of many runs")
                .totalRuns(5)
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.findByMinimumRuns(4)).thenReturn(List.of(manyRuns));

        List<RunAnalysisDocument> found = repository.findByMinimumRuns(4);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTotalRuns()).isEqualTo(5);
    }

    @Test
    void shouldFindByCreatedAtAfter() {
        RunAnalysisDocument recentDoc = RunAnalysisDocument.builder()
                .id(2L)
                .documentId(UUID.randomUUID())
                .activityIds("RECENT1")
                .queryText("Recent analysis")
                .analysisContent("Recent content")
                .totalRuns(1)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        when(repository.findByCreatedAtAfterOrderByCreatedAtDesc(thirtyDaysAgo))
                .thenReturn(List.of(recentDoc));

        List<RunAnalysisDocument> found = repository.findByCreatedAtAfterOrderByCreatedAtDesc(thirtyDaysAgo);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getActivityIds()).isEqualTo("RECENT1");
    }

    private RunAnalysisDocument createDocument(Long id, String activityIds, LocalDateTime createdAt) {
        return RunAnalysisDocument.builder()
                .id(id)
                .documentId(UUID.randomUUID())
                .activityIds(activityIds)
                .queryText("Query")
                .analysisContent("Content")
                .totalRuns(1)
                .createdAt(createdAt)
                .build();
    }
}
