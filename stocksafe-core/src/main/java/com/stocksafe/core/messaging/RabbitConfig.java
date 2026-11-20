package com.stocksafe.core.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Core publishes to the topic exchange and listens to alert events (for live updates). */
@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "stocksafe.events";
    public static final String DLX = "stocksafe.dlx";
    public static final String ALERTS_QUEUE = "core.alerts";
    public static final String ALERTS_DLQ = "core.alerts.dlq";

    @Bean TopicExchange eventsExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }

    @Bean Queue coreAlertsQueue() {
        return QueueBuilder.durable(ALERTS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", ALERTS_DLQ).build();
    }

    @Bean Queue coreAlertsDlq() { return QueueBuilder.durable(ALERTS_DLQ).build(); }

    @Bean Binding coreAlertsBinding(Queue coreAlertsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(coreAlertsQueue).to(eventsExchange).with("alert.*");
    }

    @Bean Binding coreAlertsDlqBinding(Queue coreAlertsDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(coreAlertsDlq).to(deadLetterExchange).with(ALERTS_DLQ);
    }
}
