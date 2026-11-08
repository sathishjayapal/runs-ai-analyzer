package me.sathish.runs_ai_analyzer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.sathish.runs_ai_analyzer.config.CorrelationIdFilter;
import me.sathish.runs_ai_analyzer.config.RabbitMQListenerConfiguration;
import me.sathish.runs_ai_analyzer.dto.GarminRunEvent;
import me.sathish.runs_ai_analyzer.repository.AnalysisProcessingLogRepository;
import me.sathish.runs_ai_analyzer.service.GarminEventListener;
import me.sathish.runs_ai_analyzer.service.RagStorageService;
import me.sathish.runs_ai_analyzer.service.RunAnalysisBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-broker check that the Garmin listener still consumes a message exactly the way runs-app publishes
 * the OPS event (a JSON string passed through a Jackson-converting RabbitTemplate) now that it also reads the
 * AMQP correlationId header into the MDC. Uses the app's own RabbitMQListenerConfiguration (same listener
 * factory and converter) but not the full application context, which needs AI keys and a config server.
 */
@SpringBootTest(classes = GarminEventListenerAmqpBindingTest.TestConfig.class)
@Testcontainers
class GarminEventListenerAmqpBindingTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    static final AtomicReference<String> correlationIdSeenByTheService = new AtomicReference<>();
    static final AtomicReference<GarminRunEvent> eventSeenByTheService = new AtomicReference<>();

    @Configuration
    @EnableRabbit
    // App config first so Boot's auto-configured listener factory backs off, as in the real application.
    @Import({RabbitMQListenerConfiguration.class, RabbitAutoConfiguration.class})
    static class TestConfig {

        @Bean
        Queue analyzerQueue() {
            // In production eventstracker declares this queue; here the test broker is empty.
            return new Queue(RabbitMQListenerConfiguration.ANALYZER_QUEUE, true);
        }

        @Bean
        AnalysisProcessingLogRepository processingLogRepository() {
            return Mockito.mock(AnalysisProcessingLogRepository.class);
        }

        @Bean
        RagStorageService ragStorageService() {
            return Mockito.mock(RagStorageService.class);
        }

        @Bean
        RunAnalysisBatchService runAnalysisBatchService() {
            RunAnalysisBatchService batch = Mockito.mock(RunAnalysisBatchService.class);
            Mockito.doAnswer(invocation -> {
                correlationIdSeenByTheService.set(MDC.get(CorrelationIdFilter.MDC_KEY));
                eventSeenByTheService.set(invocation.getArgument(0));
                return null;
            }).when(batch).queueForAnalysis(Mockito.any());
            return batch;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        GarminEventListener garminEventListener(AnalysisProcessingLogRepository repository,
                                                RunAnalysisBatchService batch,
                                                RagStorageService rag,
                                                ObjectMapper objectMapper) {
            return new GarminEventListener(repository, batch, rag, objectMapper);
        }
    }

    @Autowired
    private ConnectionFactory connectionFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        correlationIdSeenByTheService.set(null);
        eventSeenByTheService.set(null);
    }

    /** Same shape runs-app uses for the OPS routing key: convertAndSend(JSON string) + correlationId. */
    private void publishLikeRunsApp(String correlationId) throws Exception {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "eventType", "GARMIN_CSV_RUN",
                "activityId", "A1",
                "databaseId", 1,
                "status", "SUCCESS"));
        template.convertAndSend("", RabbitMQListenerConfiguration.ANALYZER_QUEUE, json, message -> {
            if (correlationId != null) {
                message.getMessageProperties().setCorrelationId(correlationId);
            }
            return message;
        });
    }

    @Test
    void payloadStillBindsAndTheMessageCorrelationIdReachesTheMdc() throws Exception {
        publishLikeRunsApp("file-corr-42");

        await().atMost(15, SECONDS).untilAsserted(() -> assertThat(eventSeenByTheService.get()).isNotNull());
        assertThat(eventSeenByTheService.get().getActivityId()).isEqualTo("A1");
        assertThat(eventSeenByTheService.get().getDatabaseId()).isEqualTo(1L);
        assertThat(correlationIdSeenByTheService.get()).isEqualTo("file-corr-42");
    }

    @Test
    void messageWithoutCorrelationIdIsStillProcessedWithAGeneratedOne() throws Exception {
        publishLikeRunsApp(null);

        await().atMost(15, SECONDS).untilAsserted(() -> assertThat(eventSeenByTheService.get()).isNotNull());
        assertThat(correlationIdSeenByTheService.get()).isNotBlank();
    }
}
