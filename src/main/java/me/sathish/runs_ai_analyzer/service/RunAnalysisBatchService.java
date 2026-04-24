package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.GarminRunEvent;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunAnalysisBatchService {

    private final AnalysisProcessingLogRepository processingLogRepository;
    private final RunAnalysisService analysisService;
    private final RestTemplate restTemplate;

    @Value("${runs-app.base-url:http://localhost:8080}")
    private String runsAppBaseUrl;

    @Value("${analysis.batch.size:5}")
    private int batchSize;

    @Value("${analysis.batch.window-minutes:60}")
    private int batchWindowMinutes;

    private final Map<String, GarminRunEvent> pendingEvents = new ConcurrentHashMap<>();

    @Async
    public void queueForAnalysis(GarminRunEvent event) {
        String key = event.getActivityId() + "_" + event.getDatabaseId();
        pendingEvents.put(key, event);
        log.debug("Added event to pending queue: key={}, queueSize={}", key, pendingEvents.size());
    }

    @Scheduled(fixedDelayString = "${analysis.batch.interval-ms:30000}")
    @Transactional
    public void processBatch() {
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Processing batch of {} pending events", pendingEvents.size());

        List<GarminRunEvent> eventsToProcess = new ArrayList<>(pendingEvents.values());
        pendingEvents.clear();

        Map<String, List<GarminRunEvent>> groupedByUser = eventsToProcess.stream()
                .collect(Collectors.groupingBy(e -> String.valueOf(e.getDatabaseId())));

        for (Map.Entry<String, List<GarminRunEvent>> entry : groupedByUser.entrySet()) {
            List<GarminRunEvent> userEvents = entry.getValue();
            
            if (userEvents.size() >= batchSize || shouldProcessNow(userEvents)) {
                processEventsForUser(userEvents);
            } else {
                userEvents.forEach(this::queueForAnalysis);
            }
        }
    }

    private boolean shouldProcessNow(List<GarminRunEvent> events) {
        if (events.isEmpty()) return false;
        
        LocalDateTime oldestEvent = events.stream()
                .map(e -> processingLogRepository
                        .findByActivityIdAndDatabaseId(e.getActivityId(), e.getDatabaseId())
                        .map(AnalysisProcessingLog::getCreatedAt)
                        .orElse(LocalDateTime.now()))
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
        
        return oldestEvent.isBefore(LocalDateTime.now().minusMinutes(batchWindowMinutes));
    }

    private void processEventsForUser(List<GarminRunEvent> events) {
        if (events.isEmpty()) return;

        log.info("Processing {} events for batch analysis", events.size());

        List<Long> dbIds = events.stream()
                .map(GarminRunEvent::getDatabaseId)
                .distinct()
                .collect(Collectors.toList());

        try {
            List<GarminRunDataDTO> runs = fetchRunsFromRunsApp(dbIds);
            
            if (runs.isEmpty()) {
                log.warn("No runs fetched from runs-app for dbIds: {}", dbIds);
                events.forEach(e -> markProcessingStatus(e, ProcessingStatus.SKIPPED, 
                        "No data found in runs-app"));
                return;
            }

            List<GarminRunDataDTO> runningActivities = runs.stream()
                    .filter(r -> "running".equalsIgnoreCase(r.getActivityType()))
                    .collect(Collectors.toList());

            if (runningActivities.isEmpty()) {
                log.info("No running activities found in batch, skipping analysis");
                events.forEach(e -> markProcessingStatus(e, ProcessingStatus.SKIPPED, 
                        "No running activities"));
                return;
            }

            events.forEach(e -> markProcessingStatus(e, ProcessingStatus.PROCESSING, null));

            RunAnalysisResponse response = analysisService.analyzeRuns(runningActivities, false);

            events.forEach(e -> markProcessingCompleted(e, response.getDocumentId()));

            log.info("Batch analysis completed: runs={}, documentId={}, cached={}", 
                    runningActivities.size(), response.getDocumentId(), response.isCachedResult());

        } catch (Exception e) {
            log.error("Batch analysis failed: {}", e.getMessage(), e);
            events.forEach(ev -> markProcessingStatus(ev, ProcessingStatus.FAILED, e.getMessage()));
        }
    }

    private List<GarminRunDataDTO> fetchRunsFromRunsApp(List<Long> dbIds) {
        try {
            String url = runsAppBaseUrl + "/api/garminRuns/batch?ids=" + 
                    dbIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            
            log.debug("Fetching runs from runs-app: {}", url);
            
            GarminRunDataDTO[] runs = restTemplate.getForObject(url, GarminRunDataDTO[].class);
            
            if (runs == null) {
                log.warn("Received null response from runs-app");
                return Collections.emptyList();
            }
            
            log.info("Fetched {} runs from runs-app", runs.length);
            return Arrays.asList(runs);
            
        } catch (Exception e) {
            log.error("Failed to fetch runs from runs-app: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private void markProcessingStatus(GarminRunEvent event, ProcessingStatus status, String errorMessage) {
        processingLogRepository
                .findByActivityIdAndDatabaseId(event.getActivityId(), event.getDatabaseId())
                .ifPresent(log -> {
                    log.setProcessingStatus(status);
                    if (errorMessage != null) {
                        log.setErrorMessage(errorMessage);
                    }
                    if (status == ProcessingStatus.FAILED) {
                        log.setRetryCount(log.getRetryCount() != null ? log.getRetryCount() + 1 : 1);
                        log.setLastRetryAt(LocalDateTime.now());
                    }
                    processingLogRepository.save(log);
                });
    }

    private void markProcessingCompleted(GarminRunEvent event, UUID documentId) {
        processingLogRepository
                .findByActivityIdAndDatabaseId(event.getActivityId(), event.getDatabaseId())
                .ifPresent(log -> {
                    log.setProcessingStatus(ProcessingStatus.COMPLETED);
                    log.setDocumentId(documentId != null ? documentId.toString() : null);
                    log.setProcessedAt(LocalDateTime.now());
                    processingLogRepository.save(log);
                });
    }
}
