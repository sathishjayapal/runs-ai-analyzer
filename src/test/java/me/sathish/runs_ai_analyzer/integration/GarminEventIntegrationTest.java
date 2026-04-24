package me.sathish.runs_ai_analyzer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.sathish.runs_ai_analyzer.config.RabbitMQListenerConfiguration;
import me.sathish.runs_ai_analyzer.dto.GarminRunEvent;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@Testcontainers
class GarminEventIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("runs-app.base-url", () -> "http://localhost:9999");
        registry.add("reconciliation.enabled", () -> "false");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AnalysisProcessingLogRepository processingLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        processingLogRepository.deleteAll();
    }

    @Test
    void shouldProcessGarminRunEventAndCreateLog() throws Exception {
        GarminRunEvent event = new GarminRunEvent();
        event.setEventType("GARMIN_CSV_RUN");
        event.setActivityId("test-activity-123");
        event.setActivityName("Morning Run");
        event.setActivityDate(LocalDateTime.now());
        event.setDistance("5.5");
        event.setElapsedTime("00:28:30");
        event.setDatabaseId(1001L);
        event.setStatus("SUCCESS");
        event.setFileName("test.csv");
        event.setActivityType("running");
        event.setMaxHeartRate("165");
        event.setCalories("450");

        String message = objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(
                RabbitMQListenerConfiguration.GARMIN_EXCHANGE,
                "sathishprojects.garmin.ops.event",
                message);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Optional<AnalysisProcessingLog> log = processingLogRepository
                    .findByActivityIdAndDatabaseId("test-activity-123", 1001L);
            
            assertThat(log).isPresent();
            assertThat(log.get().getEventType()).isEqualTo("GARMIN_CSV_RUN");
            assertThat(log.get().getProcessingStatus()).isIn(
                    ProcessingStatus.PENDING, 
                    ProcessingStatus.PROCESSING);
        });
    }

    @Test
    void shouldNotProcessDuplicateEvent() throws Exception {
        AnalysisProcessingLog existingLog = AnalysisProcessingLog.builder()
                .activityId("duplicate-activity")
                .databaseId(2001L)
                .eventType("GARMIN_CSV_RUN")
                .processingStatus(ProcessingStatus.COMPLETED)
                .documentId("existing-doc-id")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .build();
        
        processingLogRepository.save(existingLog);

        GarminRunEvent event = new GarminRunEvent();
        event.setEventType("GARMIN_CSV_RUN");
        event.setActivityId("duplicate-activity");
        event.setActivityName("Duplicate Run");
        event.setDatabaseId(2001L);
        event.setStatus("SUCCESS");
        event.setActivityType("running");

        String message = objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(
                RabbitMQListenerConfiguration.GARMIN_EXCHANGE,
                "sathishprojects.garmin.ops.event",
                message);

        Thread.sleep(2000);

        long count = processingLogRepository.count();
        assertThat(count).isEqualTo(1);
        
        AnalysisProcessingLog log = processingLogRepository
                .findByActivityIdAndDatabaseId("duplicate-activity", 2001L)
                .orElseThrow();
        
        assertThat(log.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(log.getDocumentId()).isEqualTo("existing-doc-id");
    }

    @Test
    void shouldSkipNonSuccessEvents() throws Exception {
        GarminRunEvent event = new GarminRunEvent();
        event.setEventType("GARMIN_CSV_RUN");
        event.setActivityId("failed-activity");
        event.setActivityName("Failed Run");
        event.setDatabaseId(3001L);
        event.setStatus("FAILED");
        event.setErrorMessage("Import failed");
        event.setActivityType("running");

        String message = objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(
                RabbitMQListenerConfiguration.GARMIN_EXCHANGE,
                "sathishprojects.garmin.ops.event",
                message);

        Thread.sleep(2000);

        Optional<AnalysisProcessingLog> log = processingLogRepository
                .findByActivityIdAndDatabaseId("failed-activity", 3001L);
        
        assertThat(log).isEmpty();
    }
}
