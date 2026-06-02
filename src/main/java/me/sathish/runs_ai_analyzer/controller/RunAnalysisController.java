package me.sathish.runs_ai_analyzer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisRequest;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.AnalysisJob;
import me.sathish.runs_ai_analyzer.service.AnalysisJobService;
import me.sathish.runs_ai_analyzer.service.RunAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Run Analysis", description = "AI-powered Garmin run analysis endpoints")
public class RunAnalysisController {

    private final RunAnalysisService runAnalysisService;
    private final AnalysisJobService analysisJobService;

    @PostMapping("/analyze")
    @Operation(
            summary = "Analyze Garmin run data",
            description = "Submits Garmin run data for AI-powered analysis using Anthropic Claude. " +
                    "Set forceRefresh=true to bypass RAG cache and get fresh LLM analysis."
    )
    @ApiResponse(responseCode = "200", description = "Analysis completed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    public ResponseEntity<RunAnalysisResponse> analyzeRuns(
            @Valid @RequestBody RunAnalysisRequest request) {
        log.info("Received analysis request for {} run(s), forceRefresh: {}", 
                request.getRuns().size(), request.isForceRefresh());
        RunAnalysisResponse response = runAnalysisService.analyzeRuns(
                request.getRuns(), request.isForceRefresh());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/check")
    @Operation(
            summary = "Check if data contains running activities",
            description = "Quickly checks if the submitted data contains analyzable running activities"
    )
    @ApiResponse(responseCode = "200", description = "Check completed")
    public ResponseEntity<Map<String, Object>> checkForRunData(
            @Valid @RequestBody RunAnalysisRequest request) {
        log.info("Checking {} record(s) for run data", request.getRuns().size());
        boolean hasRunData = runAnalysisService.containsRunData(request.getRuns());

        long runCount = request.getRuns().stream()
                .filter(r -> "running".equalsIgnoreCase(r.getActivityType()))
                .count();

        return ResponseEntity.ok(Map.of(
                "containsRunData", hasRunData,
                "totalRecords", request.getRuns().size(),
                "runningActivityCount", runCount
        ));
    }

    @PostMapping("/analyze/async")
    @Operation(
            summary = "Submit run analysis asynchronously",
            description = "Immediately returns a jobId. Poll /analyze/status/{jobId} to check progress, then fetch result from /analyze/result/{jobId}."
    )
    @ApiResponse(responseCode = "202", description = "Analysis job accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    public ResponseEntity<Map<String, Object>> analyzeRunsAsync(
            @Valid @RequestBody RunAnalysisRequest request) {
        AnalysisJob job = analysisJobService.createJob();
        log.info("Accepted async analysis job={} for {} run(s)", job.getId(), request.getRuns().size());
        analysisJobService.runAnalysisAsync(job.getId(), request.getRuns(), request.isForceRefresh());
        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "message", "Analysis started. Poll /api/v1/analysis/analyze/status/" + job.getId() + " for progress."
        ));
    }

    @GetMapping("/analyze/status/{jobId}")
    @Operation(summary = "Poll async analysis job status")
    @ApiResponse(responseCode = "200", description = "Job status returned")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ResponseEntity<Map<String, Object>> getAnalysisStatus(@PathVariable UUID jobId) {
        return analysisJobService.getJob(jobId)
                .map(job -> {
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("jobId", job.getId());
                    body.put("status", job.getStatus());
                    body.put("createdAt", job.getCreatedAt());
                    if (job.getUpdatedAt() != null) body.put("updatedAt", job.getUpdatedAt());
                    if (job.getCompletedAt() != null) body.put("completedAt", job.getCompletedAt());
                    if (job.getErrorMessage() != null) body.put("errorMessage", job.getErrorMessage());
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/analyze/result/{jobId}")
    @Operation(summary = "Fetch completed async analysis result")
    @ApiResponse(responseCode = "200", description = "Analysis result returned")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @ApiResponse(responseCode = "409", description = "Job not yet completed")
    public ResponseEntity<?> getAnalysisResult(@PathVariable UUID jobId) {
        return analysisJobService.getJob(jobId)
                .map(job -> switch (job.getStatus()) {
                    case DONE -> ResponseEntity.ok(job.getResult());
                    case FAILED -> ResponseEntity.status(500)
                            .body(Map.of("error", job.getErrorMessage() != null ? job.getErrorMessage() : "Analysis failed"));
                    default -> ResponseEntity.status(409)
                            .body(Map.of("status", job.getStatus(), "message", "Analysis not yet complete"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/analyze/single")
    @Operation(
            summary = "Analyze a single run",
            description = "Analyzes a single Garmin run activity"
    )
    @ApiResponse(responseCode = "200", description = "Analysis completed")
    @ApiResponse(responseCode = "400", description = "Invalid run data or not a running activity")
    public ResponseEntity<RunAnalysisResponse> analyzeSingleRun(
            @Valid @RequestBody GarminRunDataDTO run) {
        log.info("Analyzing single run: {}", run.getActivityName());

        if (!"running".equalsIgnoreCase(run.getActivityType())) {
            return ResponseEntity.badRequest()
                    .body(RunAnalysisResponse.builder()
                            .containsRunData(false)
                            .summary("The provided activity is not a running activity: " + run.getActivityType())
                            .build());
        }

        RunAnalysisResponse response = runAnalysisService.analyzeRuns(List.of(run));
        return ResponseEntity.ok(response);
    }
}
