package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.AnalysisJob;
import me.sathish.runs_ai_analyzer.repository.AnalysisJobRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisJobService {

    private final AnalysisJobRepository jobRepository;
    private final RunAnalysisService runAnalysisService;

    @Transactional
    public AnalysisJob createJob() {
        AnalysisJob job = AnalysisJob.builder()
                .id(UUID.randomUUID())
                .status(AnalysisJob.Status.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        return jobRepository.save(job);
    }

    @Async
    @Transactional
    public void runAnalysisAsync(UUID jobId, List<GarminRunDataDTO> runs, boolean forceRefresh) {
        log.info("Starting async analysis for jobId={}", jobId);

        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        job.setStatus(AnalysisJob.Status.PROCESSING);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);

        try {
            RunAnalysisResponse result = runAnalysisService.analyzeRuns(runs, forceRefresh);

            job.setStatus(AnalysisJob.Status.DONE);
            job.setResult(result);
            job.setCompletedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);

            log.info("Async analysis completed for jobId={}", jobId);
        } catch (Exception e) {
            log.error("Async analysis failed for jobId={}: {}", jobId, e.getMessage(), e);
            job.setStatus(AnalysisJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        }
    }

    public Optional<AnalysisJob> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }
}
