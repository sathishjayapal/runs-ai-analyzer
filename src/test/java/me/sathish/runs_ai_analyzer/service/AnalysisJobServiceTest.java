package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.AnalysisJob;
import me.sathish.runs_ai_analyzer.repository.AnalysisJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceTest {

    @Mock
    private AnalysisJobRepository jobRepository;

    @Mock
    private RunAnalysisService runAnalysisService;

    private AnalysisJobService service;
    private AnalysisJob job;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobService(jobRepository, runAnalysisService);
        job = AnalysisJob.builder().id(UUID.randomUUID()).status(AnalysisJob.Status.PENDING)
                .createdAt(LocalDateTime.now()).build();
        lenient().when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        lenient().when(jobRepository.save(any(AnalysisJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void successfulAnalysisMarksJobDone() {
        RunAnalysisResponse result = RunAnalysisResponse.builder().summary("ok").build();
        when(runAnalysisService.analyzeRuns(anyList(), anyBoolean())).thenReturn(result);

        service.runAnalysisAsync(job.getId(), List.of(GarminRunDataDTO.builder().build()), false);

        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.DONE);
        assertThat(job.getResult()).isSameAs(result);
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void failedAnalysisMarksJobFailed() {
        when(runAnalysisService.analyzeRuns(anyList(), anyBoolean())).thenThrow(new IllegalStateException("LLM down"));

        service.runAnalysisAsync(job.getId(), List.of(GarminRunDataDTO.builder().build()), false);

        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("LLM down");
    }

    @Test
    void longRunningJobIsNotWrappedInOneTransaction() throws Exception {
        // Regression guard: a single transaction around the LLM call let one failed query
        // roll back the stored analysis (UnexpectedRollbackException).
        var method = AnalysisJobService.class.getMethod("runAnalysisAsync", UUID.class, List.class, boolean.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RunAnalysisBatchService.class.getMethod("processBatch").isAnnotationPresent(Transactional.class)).isFalse();
    }
}
