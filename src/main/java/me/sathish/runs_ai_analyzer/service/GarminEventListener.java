package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.config.RabbitMQListenerConfiguration;
import me.sathish.runs_ai_analyzer.dto.GarminRunEvent;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class GarminEventListener {

    private final AnalysisProcessingLogRepository processingLogRepository;
    private final RunAnalysisBatchService batchService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQListenerConfiguration.ANALYZER_QUEUE)
    @Transactional
    public void handleGarminRunEvent(@Payload String message) {
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

        if (isAlreadyProcessed(event)) {
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
