package me.sathish.runs_ai_analyzer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import me.sathish.runs_ai_analyzer.dto.RagAnswerResponse;
import me.sathish.runs_ai_analyzer.dto.RagSearchRequest;
import me.sathish.runs_ai_analyzer.dto.RagSearchResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.service.RagAnswerService;
import me.sathish.runs_ai_analyzer.service.RagStorageService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "RAG Search", description = "Search and retrieve past run analyses using RAG")
public class RagSearchController {

    private final RagStorageService ragStorageService;
    private final RagAnswerService ragAnswerService;

    @PostMapping("/search")
    @Operation(
            summary = "Search similar analyses",
            description = "Search for similar run analyses using semantic similarity"
    )
    @ApiResponse(responseCode = "200", description = "Search completed successfully")
    public ResponseEntity<RagSearchResponse> searchAnalyses(@RequestBody RagSearchRequest request) {
        log.info("Searching for analyses with query: '{}'", request.getQuery());

        List<Document> similarDocs = ragStorageService.searchSimilarAnalyses(
                request.getQuery(),
                request.getTopK() != null ? request.getTopK() : 5
        );

        List<RagSearchResponse.SearchResult> results = similarDocs.stream()
                .map(doc -> RagSearchResponse.SearchResult.builder()
                        .documentId(doc.getId())
                        .content(doc.getText())
                        .metadata(doc.getMetadata())
                        .build())
                .toList();

        return ResponseEntity.ok(RagSearchResponse.builder()
                .query(request.getQuery())
                .results(results)
                .totalResults(results.size())
                .build());
    }

    @PostMapping("/ask")
    @Operation(
            summary = "Ask the AI running coach",
            description = "Gathers the runner's recent analyses plus the stored analyses and journal entries "
                    + "most similar to the question, and has the LLM answer with concrete next steps. "
                    + "Slower than /search because it calls the LLM. Failures to reach an AI model come back "
                    + "as 200 with an 'error' field so the UI can show them inline."
    )
    @ApiResponse(responseCode = "200", description = "Answer (or an 'error' message if no AI model was reachable)")
    @ApiResponse(responseCode = "400", description = "Query is blank")
    public ResponseEntity<RagAnswerResponse> ask(@Valid @RequestBody RagSearchRequest request) {
        log.info("Asking the AI coach: '{}'", request.getQuery());
        return ResponseEntity.ok(ragAnswerService.answer(request.getQuery(), request.getTopK()));
    }

    @GetMapping("/recent")
    @Operation(
            summary = "Get recent analyses",
            description = "Retrieve the most recent run analyses"
    )
    @ApiResponse(responseCode = "200", description = "Recent analyses retrieved")
    public ResponseEntity<List<RunAnalysisDocument>> getRecentAnalyses(
            @RequestParam(defaultValue = "10") int limit) {
        log.info("Fetching {} most recent analyses", limit);
        List<RunAnalysisDocument> recent = ragStorageService.getRecentAnalyses(limit);
        return ResponseEntity.ok(recent);
    }

    @GetMapping("/document/{documentId}")
    @Operation(
            summary = "Get analysis by document ID",
            description = "Retrieve a specific run analysis by its document ID"
    )
    @ApiResponse(responseCode = "200", description = "Document found")
    @ApiResponse(responseCode = "404", description = "Document not found")
    public ResponseEntity<RunAnalysisDocument> getAnalysisByDocumentId(
            @PathVariable UUID documentId) {
        log.error("Fetching analysis document: {}", documentId);
        return ragStorageService.findByDocumentId(documentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/activity/{activityId}")
    @Operation(
            summary = "Find analyses by activity ID",
            description = "Find all analyses that include a specific activity"
    )
    @ApiResponse(responseCode = "200", description = "Analyses found")
    public ResponseEntity<List<RunAnalysisDocument>> findByActivityId(
            @PathVariable String activityId) {
        log.info("Searching analyses containing activity: {}", activityId);
        List<RunAnalysisDocument> analyses = ragStorageService.findAnalysesByActivityId(activityId);
        return ResponseEntity.ok(analyses);
    }

    @GetMapping("/distance")
    @Operation(
            summary = "Find analyses by minimum distance",
            description = "Find analyses with at least the specified total distance"
    )
    @ApiResponse(responseCode = "200", description = "Analyses found")
    public ResponseEntity<List<RunAnalysisDocument>> findByMinimumDistance(
            @RequestParam Double minDistanceMiles) {
        log.info("Searching analyses with minimum distance: {} mi", minDistanceMiles);
        List<RunAnalysisDocument> analyses = ragStorageService.findAnalysesByMinimumDistance(minDistanceMiles);
        return ResponseEntity.ok(analyses);
    }
}
