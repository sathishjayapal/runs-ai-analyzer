package me.sathish.runs_ai_analyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQListenerConfiguration {

    // Use eventstracker's existing queue infrastructure
    public static final String GARMIN_EXCHANGE = "x.sathishprojects.garmin.events.exchange";
    public static final String GARMIN_DLX_EXCHANGE = "x.sathishprojects.garmin.events.dlx.exchange";
    public static final String ANALYZER_QUEUE = "q.sathishprojects.garmin.ops.events";
    public static final String DLQ_QUEUE = "dlq.sathishprojects.garmin.ops.events";
    public static final String GARMIN_OPS_ROUTING_KEY = "sathishprojects.garmin.ops.*";

    @Value("${rabbitmq.listener.prefetch:10}")
    private int prefetchCount;

    @Value("${rabbitmq.listener.concurrency:2}")
    private int concurrency;

    @Value("${rabbitmq.listener.max-concurrency:5}")
    private int maxConcurrency;

    // No need to create queues - eventstracker already provisions them
    // We just consume from the existing GARMIN_OPS_EVENTS_QUEUE

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        
        factory.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        factory.setPrefetchCount(prefetchCount);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(maxConcurrency);
        factory.setDefaultRequeueRejected(false);
        
        log.info("RabbitMQ listener factory configured: prefetch={}, concurrency={}-{}", 
                prefetchCount, concurrency, maxConcurrency);
        
        return factory;
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        // Failed messages will go to eventstracker's DLQ via x-dead-letter-exchange
        return new RepublishMessageRecoverer(rabbitTemplate, GARMIN_DLX_EXCHANGE, GARMIN_OPS_ROUTING_KEY);
    }
}
