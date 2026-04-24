package me.sathish.runs_ai_analyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQConfiguration {

    // For publishing analysis results back to eventstracker (if needed)
    public static final String GARMIN_EXCHANGE = "x.sathishprojects.garmin.events.exchange";
    public static final String GARMIN_OPS_ROUTING_KEY = "sathishprojects.garmin.ops.analysis";

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned ->
                log.warn("Analyzer event returned by broker. exchange={}, routingKey={}, replyCode={}, replyText={}",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText()));
        return rabbitTemplate;
    }
}
