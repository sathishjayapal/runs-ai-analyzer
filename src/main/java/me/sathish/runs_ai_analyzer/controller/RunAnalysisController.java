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
import me.sathish.runs_ai_analyzer.service.RunAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Run Analysis", description = "AI-powered Garmin run analysis endpoints")
public class RunAnalysisController {

    private final RunAnalysisService runAnalysisService;

    @PostMapping("/analyze")
    @Operation(
            summary = "Analyze Garmin run data",
            description = "Submits Garmin run data for AI-powered analysis using Anthropic Claude"
    )
    @ApiResponse(responseCode = "200", description = "Analysis completed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    public ResponseEntity<RunAnalysisResponse> analyzeRuns(
            @Valid @RequestBody RunAnalysisRequest request) {
        log.info("Received analysis request for {} run(s)", request.getRuns().size());
        RunAnalysisResponse response = runAnalysisService.analyzeRuns(request.getRuns());
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
