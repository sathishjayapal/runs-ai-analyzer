package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GarminEventListenerTest {

    @Mock
    private AnalysisProcessingLogRepository processingLogRepository;

    @Mock
    private RunAnalysisBatchService batchService;

    @Mock
    private RagStorageService ragStorageService;

    private GarminEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new GarminEventListener(processingLogRepository, batchService, ragStorageService, new ObjectMapper());
    }

    @Test
    void newRunEvictsCachedAnalysesAndQueuesRun() {
        when(processingLogRepository.findByActivityIdAndDatabaseId("A1", 1L)).thenReturn(Optional.empty());

        listener.handleGarminRunEvent(event("A1", 1L, "SUCCESS"));

        verify(ragStorageService).evictCachedAnalyses();
        verify(batchService).queueForAnalysis(any());
    }

    @Test
    void duplicateEventDoesNotEvict() {
        when(processingLogRepository.findByActivityIdAndDatabaseId("A1", 1L))
                .thenReturn(Optional.of(processedLog("A1", 1L)));

        listener.handleGarminRunEvent(event("A1", 1L, "SUCCESS"));

        verify(ragStorageService, never()).evictCachedAnalyses();
        verify(batchService, never()).queueForAnalysis(any());
    }

    @Test
    void updatedRunEvictsEvenWhenAlreadyProcessed() {
        when(processingLogRepository.findByActivityIdAndDatabaseId("A1", 1L))
                .thenReturn(Optional.of(processedLog("A1", 1L)));

        listener.handleGarminRunEvent(event("A1", 1L, "UPDATED"));

        verify(ragStorageService).evictCachedAnalyses();
        verify(batchService, never()).queueForAnalysis(any());
    }

    @Test
    void failedImportEventIsIgnored() {
        listener.handleGarminRunEvent(event("A1", 1L, "FAILED"));

        verifyNoInteractions(ragStorageService, batchService, processingLogRepository);
    }

    private static String event(String activityId, long databaseId, String status) {
        return """
                {"eventType":"GARMIN_CSV_RUN","activityId":"%s","databaseId":%d,"status":"%s","activityType":"running"}
                """.formatted(activityId, databaseId, status);
    }

    private static AnalysisProcessingLog processedLog(String activityId, long databaseId) {
        return AnalysisProcessingLog.builder()
                .activityId(activityId)
                .databaseId(databaseId)
                .eventType("GARMIN_CSV_RUN")
                .processingStatus(ProcessingStatus.COMPLETED)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
    }
}
