package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.config.CorrelationIdFilter;
import me.sathish.runs_ai_analyzer.config.RabbitMQListenerConfiguration;
import me.sathish.runs_ai_analyzer.dto.GarminRunEvent;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class GarminEventListener {

    private final AnalysisProcessingLogRepository processingLogRepository;
    private final RunAnalysisBatchService batchService;
    private final RagStorageService ragStorageService;
    private final ObjectMapper objectMapper;

    /**
     * runs-app stamps the AMQP correlationId property on every Garmin event (one id per CSV file).
     * Reading it here puts it in the MDC so this service's log lines for the event carry the same id
     * and a whole import can be followed across runs-app, eventstracker and this service in Loki.
     * Only reads a header; the payload handling (a String the converter has already un-escaped) is unchanged.
     */
    @RabbitListener(queues = RabbitMQListenerConfiguration.ANALYZER_QUEUE)
    @Transactional
    public void handleGarminRunEvent(
            @Payload String message,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId) {
        MDC.put(CorrelationIdFilter.MDC_KEY,
                correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId);
        try {
            processEvent(message);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void processEvent(String message) {
        log.debug("Received Garmin event message: {}", message);

        GarminRunEvent event;
        try {
            event = objectMapper.readValue(message, GarminRunEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize Garmin event: {}", e.getMessage(), e);
            return;
        }

        if (!"SUCCESS".equals(event.getStatus()) && !"UPDATED".equals(event.getStatus())) {
            log.debug("Skipping non-success event: activityId={}, status={}", 
                    event.getActivityId(), event.getStatus());
            return;
        }

        if (event.getDatabaseId() == null) {
            log.warn("Event missing databaseId, cannot process: activityId={}", event.getActivityId());
            return;
        }

        boolean alreadyProcessed = isAlreadyProcessed(event);

        // New or changed run data means any cached "Analyze My Runs" result is out of date.
        // UPDATED events evict even if this activity was processed before, because its data changed.
        if (!alreadyProcessed || "UPDATED".equals(event.getStatus())) {
            int evicted = ragStorageService.evictCachedAnalyses();
            log.debug("Run data changed (activityId={}, status={}); evicted {} cached analysis document(s)",
                    event.getActivityId(), event.getStatus(), evicted);
        }

        if (alreadyProcessed) {
            log.debug("Event already processed (idempotency check): activityId={}, dbId={}", 
                    event.getActivityId(), event.getDatabaseId());
            return;
        }

        createProcessingLog(event);

        try {
            batchService.queueForAnalysis(event);
            log.info("Queued Garmin run for analysis: activityId={}, dbId={}", 
                    event.getActivityId(), event.getDatabaseId());
        } catch (Exception e) {
            log.error("Failed to queue run for analysis: activityId={}, error={}", 
                    event.getActivityId(), e.getMessage(), e);
            markProcessingFailed(event, e.getMessage());
            throw e;
        }
    }

    private boolean isAlreadyProcessed(GarminRunEvent event) {
        return processingLogRepository
                .findByActivityIdAndDatabaseId(event.getActivityId(), event.getDatabaseId())
                .filter(log -> log.getProcessingStatus() == ProcessingStatus.COMPLETED 
                            || log.getProcessingStatus() == ProcessingStatus.PROCESSING)
                .isPresent();
    }

    private void createProcessingLog(GarminRunEvent event) {
        AnalysisProcessingLog log = AnalysisProcessingLog.builder()
                .activityId(event.getActivityId())
                .databaseId(event.getDatabaseId())
                .eventType(event.getEventType())
                .processingStatus(ProcessingStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        
        processingLogRepository.save(log);
    }

    private void markProcessingFailed(GarminRunEvent event, String errorMessage) {
        processingLogRepository
                .findByActivityIdAndDatabaseId(event.getActivityId(), event.getDatabaseId())
                .ifPresent(log -> {
                    log.setProcessingStatus(ProcessingStatus.FAILED);
                    log.setErrorMessage(errorMessage);
                    log.setRetryCount(log.getRetryCount() != null ? log.getRetryCount() + 1 : 1);
                    log.setLastRetryAt(LocalDateTime.now());
                    processingLogRepository.save(log);
                });
    }
}
