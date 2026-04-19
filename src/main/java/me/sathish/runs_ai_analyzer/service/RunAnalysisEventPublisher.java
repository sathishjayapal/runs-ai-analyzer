package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.config.RabbitMQConfiguration;
import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisEvent;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RunAnalysisEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishAnalysisCompleted(RunAnalysisResponse response, List<GarminRunDataDTO> runs) {
        try {
            RunAnalysisEvent event = RunAnalysisEvent.builder()
                    .eventType(response.isCachedResult() ? "RUN_ANALYSIS_CACHE_HIT" : "RUN_ANALYSIS_COMPLETED")
                    .sourceService("runs-ai-analyzer")
                    .documentId(response.getDocumentId())
                    .cachedResult(response.isCachedResult())
                    .containsRunData(response.isContainsRunData())
                    .runCount(runs.size())
                    .activityIds(runs.stream().map(GarminRunDataDTO::getActivityId).toList())
                    .summary(response.getSummary())
                    .insights(response.getInsights())
                    .recommendations(response.getRecommendations())
                    .riskFlags(response.getRiskFlags())
                    .confidenceScore(response.getConfidenceScore())
                    .metrics(response.getMetrics())
                    .analyzedAt(response.getAnalyzedAt())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfiguration.GARMIN_EXCHANGE,
                    RabbitMQConfiguration.GARMIN_ROUTING_KEY,
                    objectMapper.writeValueAsString(event));

            log.info("Published run analysis event. type={}, cached={}, runs={}",
                    event.getEventType(), event.isCachedResult(), runs.size());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize run analysis event payload: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to publish run analysis event: {}", e.getMessage());
        }
    }
}
