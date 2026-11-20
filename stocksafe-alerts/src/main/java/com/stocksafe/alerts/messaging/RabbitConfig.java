package com.stocksafe.alerts.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * alerts.events gets tenant.*, product.* and batch.* events from core.
 * A message that fails 5 times is rejected and RabbitMQ moves it to alerts.events.dlq.
 */
@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "stocksafe.events";
    public static final String DLX = "stocksafe.dlx";
    public static final String QUEUE = "alerts.events";
    public static final String DLQ = "alerts.events.dlq";

    @Bean TopicExchange eventsExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }

    @Bean Queue alertsQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ).build();
    }

    @Bean Queue alertsDlq() { return QueueBuilder.durable(DLQ).build(); }

    @Bean Declarables alertsBindings(Queue alertsQueue, Queue alertsDlq, TopicExchange eventsExchange,
                                     DirectExchange deadLetterExchange) {
        return new Declarables(
                BindingBuilder.bind(alertsQueue).to(eventsExchange).with("tenant.*"),
                BindingBuilder.bind(alertsQueue).to(eventsExchange).with("product.*"),
                BindingBuilder.bind(alertsQueue).to(eventsExchange).with("batch.*"),
                BindingBuilder.bind(alertsDlq).to(deadLetterExchange).with(DLQ));
    }
}
