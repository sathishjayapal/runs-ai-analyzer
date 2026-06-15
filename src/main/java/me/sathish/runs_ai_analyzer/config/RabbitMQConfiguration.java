package me.sathish.runs_ai_analyzer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQConfiguration {

    // For publishing analysis results back to eventstracker (if needed)
    public static final String GARMIN_EXCHANGE = "x.sathishprojects.garmin.events.exchange";
    public static final String GARMIN_OPS_ROUTING_KEY = "sathishprojects.garmin.ops.analysis";

    // For publishing journal/non-analysis events that eventstracker should audit
    // Matches binding pattern sathishprojects.garmin.api.* on q.sathishprojects.garmin.api.events
    public static final String GARMIN_API_ROUTING_KEY = "sathishprojects.garmin.api.journal";

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new JacksonJsonMessageConverter());
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
