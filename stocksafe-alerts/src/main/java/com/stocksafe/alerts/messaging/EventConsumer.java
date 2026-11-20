package com.stocksafe.alerts.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {
    private final EventHandler handler;

    public EventConsumer(EventHandler handler) {
        this.handler = handler;
    }

    /** If this throws, Spring retries with backoff (1s, 2s, 4s, 8s), then the message goes to the DLQ. */
    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onMessage(String body) {
        handler.handle(body);
    }
}
