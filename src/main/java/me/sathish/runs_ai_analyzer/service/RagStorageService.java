package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagStorageService {

    RunAnalysisDocument storeAnalysis(List<GarminRunDataDTO> runs, RunAnalysisResponse response, String queryText);

    List<Document> searchSimilarAnalyses(String query, int topK);

    /**
     * Find a cached analysis that is similar enough to the given query.
     * Returns the cached RunAnalysisDocument if similarity meets threshold and is not stale.
     *
     * @param queryText the query text to search for
     * @return Optional containing the cached analysis if found and valid
     */
    Optional<RunAnalysisDocument> findCachedAnalysis(String queryText);

    Optional<RunAnalysisDocument> findByDocumentId(UUID documentId);

    List<RunAnalysisDocument> getRecentAnalyses(int limit);

    List<RunAnalysisDocument> findAnalysesByActivityId(String activityId);

    List<RunAnalysisDocument> findAnalysesByMinimumDistance(Double minDistanceKm);
}
