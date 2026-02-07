package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.config.RagCacheProperties;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.repository.RunAnalysisDocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagStorageServiceImpl implements RagStorageService {

    private final RunAnalysisDocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final RagCacheProperties cacheProperties;

    @Override
    @Transactional
    public RunAnalysisDocument storeAnalysis(List<GarminRunDataDTO> runs, RunAnalysisResponse response, String queryText) {
        log.debug("Storing analysis for {} runs in RAG database", runs.size());

        UUID documentId = UUID.randomUUID();

        String activityIds = runs.stream()
                .map(GarminRunDataDTO::getActivityId)
                .collect(Collectors.joining(","));

        Map<String, Object> metadata = buildMetadata(runs, response, documentId);

        RunAnalysisDocument document = RunAnalysisDocument.builder()
                .documentId(documentId)
                .activityIds(activityIds)
                .queryText(queryText)
                .analysisContent(response.getRawAnalysis())
                .summary(response.getSummary())
                .totalRuns(response.getMetrics() != null ? response.getMetrics().getTotalRuns() : runs.size())
                .totalDistanceKm(response.getMetrics() != null ? response.getMetrics().getTotalDistanceKm() : null)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .build();

        RunAnalysisDocument savedDocument = documentRepository.save(document);
        log.info("Saved analysis document with ID: {}", savedDocument.getDocumentId());

        storeInVectorStore(savedDocument);

        return savedDocument;
    }

    private void storeInVectorStore(RunAnalysisDocument analysisDocument) {
        try {
            String contentForEmbedding = buildContentForEmbedding(analysisDocument);

            Map<String, Object> vectorMetadata = new HashMap<>();
            vectorMetadata.put("documentId", analysisDocument.getDocumentId().toString());
            vectorMetadata.put("totalRuns", analysisDocument.getTotalRuns());
            vectorMetadata.put("totalDistanceKm", analysisDocument.getTotalDistanceKm());
            vectorMetadata.put("createdAt", analysisDocument.getCreatedAt().toString());

            Document vectorDocument = new Document(
                    analysisDocument.getDocumentId().toString(),
                    contentForEmbedding,
                    vectorMetadata
            );

            vectorStore.add(List.of(vectorDocument));
            log.debug("Successfully stored document in vector store: {}", analysisDocument.getDocumentId());
        } catch (Exception e) {
            log.error("Failed to store document in vector store: {}", e.getMessage(), e);
        }
    }

    private String buildContentForEmbedding(RunAnalysisDocument document) {
        // Use query text as primary embedding content for better cache hit matching
        // The query text contains the run data that will be searched for
        StringBuilder content = new StringBuilder();
        content.append(document.getQueryText()).append("\n\n");
        content.append("Total Runs: ").append(document.getTotalRuns()).append("\n");
        if (document.getTotalDistanceKm() != null) {
            content.append("Total Distance: ").append(document.getTotalDistanceKm()).append(" km\n");
        }
        return content.toString();
    }

    private Map<String, Object> buildMetadata(List<GarminRunDataDTO> runs, RunAnalysisResponse response, UUID documentId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId.toString());
        metadata.put("runCount", runs.size());

        if (response.getMetrics() != null) {
            metadata.put("totalDistanceKm", response.getMetrics().getTotalDistanceKm());
            metadata.put("totalDuration", response.getMetrics().getTotalDuration());
            metadata.put("averagePace", response.getMetrics().getAveragePaceMinPerKm());
            metadata.put("averageHeartRate", response.getMetrics().getAverageHeartRate());
            metadata.put("totalCalories", response.getMetrics().getTotalCalories());
        }

        List<String> activityDates = runs.stream()
                .map(GarminRunDataDTO::getActivityDate)
                .filter(Objects::nonNull)
                .toList();
        if (!activityDates.isEmpty()) {
            metadata.put("activityDates", activityDates);
            metadata.put("dateRange", activityDates.getFirst() + " to " + activityDates.getLast());
        }

        return metadata;
    }

    @Override
    public List<Document> searchSimilarAnalyses(String query, int topK) {
        log.debug("Searching for similar analyses with query: '{}', topK: {}", query, topK);
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            
            List<Document> results = vectorStore.similaritySearch(searchRequest);
            log.debug("Found {} similar documents", results.size());
            return results;
        } catch (Exception e) {
            log.error("Error searching vector store: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public Optional<RunAnalysisDocument> findCachedAnalysis(String queryText) {
        if (!cacheProperties.isEnabled()) {
            log.debug("RAG cache is disabled, skipping cache lookup");
            return Optional.empty();
        }

        log.debug("Searching for cached analysis with similarity threshold: {}", 
                cacheProperties.getSimilarityThreshold());

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(queryText)
                    .topK(1)
                    .similarityThreshold(cacheProperties.getSimilarityThreshold())
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            if (results.isEmpty()) {
                log.debug("No cached analysis found above similarity threshold");
                return Optional.empty();
            }

            Document topResult = results.getFirst();
            String documentIdStr = (String) topResult.getMetadata().get("documentId");
            
            if (documentIdStr == null) {
                log.warn("Found similar document but missing documentId in metadata");
                return Optional.empty();
            }

            UUID documentId = UUID.fromString(documentIdStr);
            Optional<RunAnalysisDocument> cachedDoc = documentRepository.findByDocumentId(documentId);

            if (cachedDoc.isEmpty()) {
                log.warn("Document ID {} found in vector store but not in database", documentId);
                return Optional.empty();
            }

            RunAnalysisDocument document = cachedDoc.get();
            
            // Check if document is stale
            LocalDateTime staleThreshold = LocalDateTime.now().minusDays(cacheProperties.getTtlDays());
            if (document.getCreatedAt().isBefore(staleThreshold)) {
                log.debug("Cached analysis is stale (created: {}, threshold: {})", 
                        document.getCreatedAt(), staleThreshold);
                return Optional.empty();
            }

            log.info("Found valid cached analysis with document ID: {}", documentId);
            return cachedDoc;

        } catch (Exception e) {
            log.error("Error searching for cached analysis: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<RunAnalysisDocument> findByDocumentId(UUID documentId) {
        return documentRepository.findByDocumentId(documentId);
    }

    @Override
    public List<RunAnalysisDocument> getRecentAnalyses(int limit) {
        if (limit <= 10) {
            return documentRepository.findTop10ByOrderByCreatedAtDesc();
        }
        return documentRepository.findByCreatedAtAfterOrderByCreatedAtDesc(
                LocalDateTime.now().minusDays(30)
        ).stream().limit(limit).toList();
    }

    @Override
    public List<RunAnalysisDocument> findAnalysesByActivityId(String activityId) {
        return documentRepository.findByActivityIdContaining(activityId);
    }

    @Override
    public List<RunAnalysisDocument> findAnalysesByMinimumDistance(Double minDistanceKm) {
        return documentRepository.findByMinimumDistance(minDistanceKm);
    }
}
