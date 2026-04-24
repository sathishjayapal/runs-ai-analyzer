package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final AnalysisProcessingLogRepository processingLogRepository;
    private final RunAnalysisService analysisService;
    private final RestTemplate restTemplate;

    @Value("${runs-app.base-url:http://localhost:8080}")
    private String runsAppBaseUrl;

    @Value("${reconciliation.enabled:true}")
    private boolean reconciliationEnabled;

    @Value("${reconciliation.max-retries:3}")
    private int maxRetries;

    @Value("${reconciliation.retry-delay-minutes:30}")
    private int retryDelayMinutes;

    @Value("${reconciliation.lookback-days:7}")
    private int lookbackDays;

    @Scheduled(cron = "${reconciliation.cron:0 0 */6 * * *}")
    @Transactional
    public void reconcileMissedEvents() {
        if (!reconciliationEnabled) {
            log.debug("Reconciliation is disabled");
            return;
        }

        log.info("Starting reconciliation process for missed events");

        try {
            retryFailedEvents();
            
            catchUpMissedRuns();
            
            cleanupOldLogs();
            
            logReconciliationStats();
            
        } catch (Exception e) {
            log.error("Reconciliation process failed: {}", e.getMessage(), e);
        }
    }

    private void retryFailedEvents() {
        LocalDateTime retryThreshold = LocalDateTime.now().minusMinutes(retryDelayMinutes);
        
        List<AnalysisProcessingLog> failedLogs = processingLogRepository.findRetryableFailed(
                ProcessingStatus.FAILED, maxRetries, retryThreshold);

        if (failedLogs.isEmpty()) {
            log.debug("No failed events to retry");
            return;
        }

        log.info("Retrying {} failed events", failedLogs.size());

        Map<String, List<AnalysisProcessingLog>> groupedByActivity = failedLogs.stream()
                .collect(Collectors.groupingBy(AnalysisProcessingLog::getActivityId));

        for (Map.Entry<String, List<AnalysisProcessingLog>> entry : groupedByActivity.entrySet()) {
            List<AnalysisProcessingLog> logs = entry.getValue();
            
            List<Long> dbIds = logs.stream()
                    .map(AnalysisProcessingLog::getDatabaseId)
                    .distinct()
                    .collect(Collectors.toList());

            try {
                List<GarminRunDataDTO> runs = fetchRunsFromRunsApp(dbIds);
                
                if (runs.isEmpty()) {
                    logs.forEach(log -> markAsSkipped(log, "No data found in runs-app"));
                    continue;
                }

                List<GarminRunDataDTO> runningActivities = runs.stream()
                        .filter(r -> "running".equalsIgnoreCase(r.getActivityType()))
                        .collect(Collectors.toList());

                if (runningActivities.isEmpty()) {
                    logs.forEach(log -> markAsSkipped(log, "No running activities"));
                    continue;
                }

                logs.forEach(log -> {
                    log.setProcessingStatus(ProcessingStatus.PROCESSING);
                    log.setLastRetryAt(LocalDateTime.now());
                    processingLogRepository.save(log);
                });

                RunAnalysisResponse response = analysisService.analyzeRuns(runningActivities, false);

                logs.forEach(log -> markAsCompleted(log, response.getDocumentId()));

                log.info("Retry successful for {} events, documentId={}", 
                        logs.size(), response.getDocumentId());

            } catch (Exception e) {
                log.error("Retry failed for activityId={}: {}", entry.getKey(), e.getMessage());
                logs.forEach(log -> incrementRetryCount(log, e.getMessage()));
            }
        }
    }

    private void catchUpMissedRuns() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(lookbackDays);
            
            String url = runsAppBaseUrl + "/api/garminRuns/recent?since=" + since;
            log.debug("Fetching recent runs from runs-app: {}", url);
            
            GarminRunDataDTO[] recentRuns = restTemplate.getForObject(url, GarminRunDataDTO[].class);
            
            if (recentRuns == null || recentRuns.length == 0) {
                log.debug("No recent runs found in runs-app");
                return;
            }

            log.info("Found {} recent runs in runs-app, checking for missed analyses", recentRuns.length);

            List<String> activityIds = Arrays.stream(recentRuns)
                    .map(GarminRunDataDTO::getActivityId)
                    .collect(Collectors.toList());

            List<AnalysisProcessingLog> existingLogs = 
                    processingLogRepository.findByActivityIdIn(activityIds);

            Set<String> processedActivityIds = existingLogs.stream()
                    .filter(log -> log.getProcessingStatus() == ProcessingStatus.COMPLETED)
                    .map(AnalysisProcessingLog::getActivityId)
                    .collect(Collectors.toSet());

            List<GarminRunDataDTO> missedRuns = Arrays.stream(recentRuns)
                    .filter(run -> !processedActivityIds.contains(run.getActivityId()))
                    .filter(run -> "running".equalsIgnoreCase(run.getActivityType()))
                    .collect(Collectors.toList());

            if (missedRuns.isEmpty()) {
                log.info("No missed runs detected");
                return;
            }

            log.info("Detected {} missed runs, creating analysis", missedRuns.size());

            missedRuns.forEach(run -> {
                AnalysisProcessingLog log = AnalysisProcessingLog.builder()
                        .activityId(run.getActivityId())
                        .databaseId(run.getId())
                        .eventType("RECONCILIATION_CATCHUP")
                        .processingStatus(ProcessingStatus.PROCESSING)
                        .retryCount(0)
                        .createdAt(LocalDateTime.now())
                        .build();
                processingLogRepository.save(log);
            });

            RunAnalysisResponse response = analysisService.analyzeRuns(missedRuns, false);

            missedRuns.forEach(run -> {
                processingLogRepository.findByActivityIdAndDatabaseId(run.getActivityId(), run.getId())
                        .ifPresent(log -> markAsCompleted(log, response.getDocumentId()));
            });

            log.info("Catch-up analysis completed: runs={}, documentId={}", 
                    missedRuns.size(), response.getDocumentId());

        } catch (Exception e) {
            log.error("Catch-up process failed: {}", e.getMessage(), e);
        }
    }

    private void cleanupOldLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays * 2);
        
        log.debug("Cleaning up processing logs older than {}", cutoff);
    }

    private void logReconciliationStats() {
        long pending = processingLogRepository.countByStatus(ProcessingStatus.PENDING);
        long processing = processingLogRepository.countByStatus(ProcessingStatus.PROCESSING);
        long completed = processingLogRepository.countByStatus(ProcessingStatus.COMPLETED);
        long failed = processingLogRepository.countByStatus(ProcessingStatus.FAILED);
        long skipped = processingLogRepository.countByStatus(ProcessingStatus.SKIPPED);

        log.info("Reconciliation stats - Pending: {}, Processing: {}, Completed: {}, Failed: {}, Skipped: {}",
                pending, processing, completed, failed, skipped);
    }

    private List<GarminRunDataDTO> fetchRunsFromRunsApp(List<Long> dbIds) {
        try {
            String url = runsAppBaseUrl + "/api/garminRuns/batch?ids=" + 
                    dbIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            
            GarminRunDataDTO[] runs = restTemplate.getForObject(url, GarminRunDataDTO[].class);
            return runs != null ? Arrays.asList(runs) : Collections.emptyList();
            
        } catch (Exception e) {
            log.error("Failed to fetch runs from runs-app: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void markAsCompleted(AnalysisProcessingLog log, UUID documentId) {
        log.setProcessingStatus(ProcessingStatus.COMPLETED);
        log.setDocumentId(documentId != null ? documentId.toString() : null);
        log.setProcessedAt(LocalDateTime.now());
        processingLogRepository.save(log);
    }

    private void markAsSkipped(AnalysisProcessingLog log, String reason) {
        log.setProcessingStatus(ProcessingStatus.SKIPPED);
        log.setErrorMessage(reason);
        log.setProcessedAt(LocalDateTime.now());
        processingLogRepository.save(log);
    }

    private void incrementRetryCount(AnalysisProcessingLog log, String errorMessage) {
        log.setRetryCount(log.getRetryCount() != null ? log.getRetryCount() + 1 : 1);
        log.setErrorMessage(errorMessage);
        log.setLastRetryAt(LocalDateTime.now());
        
        if (log.getRetryCount() >= maxRetries) {
            log.setProcessingStatus(ProcessingStatus.FAILED);
            log.setProcessedAt(LocalDateTime.now());
        }
        
        processingLogRepository.save(log);
    }
}
