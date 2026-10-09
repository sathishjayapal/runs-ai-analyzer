package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagStorageService {

    /**
     * @param cacheKey exact-match key for the LLM input (see {@link RunAnalysisServiceImpl}); may be null
     */
    RunAnalysisDocument storeAnalysis(List<GarminRunDataDTO> runs, RunAnalysisResponse response,
                                      String queryText, String cacheKey);

    List<Document> searchSimilarAnalyses(String query, int topK);

    /**
     * Find a cached analysis for exactly the same LLM input.
     * Only non-evicted analyses created within the TTL are returned.
     *
     * @param cacheKey SHA-256 of the system prompt and run data
     * @return Optional containing the cached analysis if found and valid
     */
    Optional<RunAnalysisDocument> findCachedAnalysis(String cacheKey);

    /**
     * Evicts all cached analyses so the next request goes to the LLM.
     * Called when runs-app reports new or updated runs. Documents stay available for RAG search.
     *
     * @return number of analyses evicted
     */
    int evictCachedAnalyses();

    Optional<RunAnalysisDocument> findByDocumentId(UUID documentId);

    List<RunAnalysisDocument> getRecentAnalyses(int limit);

    List<RunAnalysisDocument> findAnalysesByActivityId(String activityId);

    List<RunAnalysisDocument> findAnalysesByMinimumDistance(Double minDistanceMiles);
}
