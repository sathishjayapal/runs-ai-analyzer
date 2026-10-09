package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagStorageServiceImpl implements RagStorageService {

    private final RunAnalysisDocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final RagCacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public RunAnalysisDocument storeAnalysis(List<GarminRunDataDTO> runs, RunAnalysisResponse response,
                                             String queryText, String cacheKey) {
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
                .totalDistanceMiles(response.getMetrics() != null ? response.getMetrics().getTotalDistanceMiles() : null)
                .metadata(metadata)
                .cacheKey(cacheKey)
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
            vectorMetadata.put("totalDistanceMiles", analysisDocument.getTotalDistanceMiles());
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
        if (document.getSummary() != null) {
            content.append("Summary: ").append(document.getSummary()).append("\n");
        }
        content.append("Total Runs: ").append(document.getTotalRuns()).append("\n");
        if (document.getTotalDistanceMiles() != null) {
            content.append("Total Distance: ").append(document.getTotalDistanceMiles()).append(" mi\n");
        }
        return content.toString();
    }

    private Map<String, Object> buildMetadata(List<GarminRunDataDTO> runs, RunAnalysisResponse response, UUID documentId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId.toString());
        metadata.put("runCount", runs.size());

        if (response.getMetrics() != null) {
            metadata.put("totalRuns", response.getMetrics().getTotalRuns());
            metadata.put("totalDistanceMiles", response.getMetrics().getTotalDistanceMiles());
            metadata.put("totalDuration", response.getMetrics().getTotalDuration());
            metadata.put("averagePace", response.getMetrics().getAveragePaceMinPerMile());
            metadata.put("averageHeartRate", response.getMetrics().getAverageHeartRate());
            metadata.put("totalCalories", response.getMetrics().getTotalCalories());
        }

        metadata.put("structuredSummary", response.getSummary());
        metadata.put("recommendations", defaultList(response.getRecommendations()));
        metadata.put("riskFlags", defaultList(response.getRiskFlags()));
        metadata.put("confidenceScore", response.getConfidenceScore());
        metadata.put("insights", objectMapper.convertValue(
                response.getInsights() != null ? response.getInsights() : List.of(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)));

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

    private List<String> defaultList(List<String> value) {
        return value != null ? value : List.of();
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
    public Optional<RunAnalysisDocument> findCachedAnalysis(String cacheKey) {
        if (!cacheProperties.isEnabled()) {
            log.debug("RAG cache is disabled, skipping cache lookup");
            return Optional.empty();
        }
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }

        try {
            LocalDateTime ttlCutoff = LocalDateTime.now().minusDays(cacheProperties.getTtlDays());
            Optional<RunAnalysisDocument> cached = documentRepository
                    .findFirstByCacheKeyAndCacheEvictedAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(cacheKey, ttlCutoff);

            if (cached.isPresent()) {
                log.info("Cache hit for run analysis (documentId={}, cacheKey={})",
                        cached.get().getDocumentId(), shortKey(cacheKey));
            } else {
                log.debug("Cache miss for run analysis (cacheKey={})", shortKey(cacheKey));
            }
            return cached;
        } catch (Exception e) {
            log.error("Error looking up cached analysis: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public int evictCachedAnalyses() {
        int evicted = documentRepository.evictAllCachedAnalyses(LocalDateTime.now());
        if (evicted > 0) {
            log.info("Evicted {} cached run analysis document(s)", evicted);
        }
        return evicted;
    }

    private static String shortKey(String cacheKey) {
        return cacheKey.length() > 12 ? cacheKey.substring(0, 12) : cacheKey;
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
    public List<RunAnalysisDocument> findAnalysesByMinimumDistance(Double minDistanceMiles) {
        return documentRepository.findByMinimumDistance(minDistanceMiles);
    }
}
