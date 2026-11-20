package com.stocksafe.alerts.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Metrics {
    /** Messages waiting in the main queue and in the dead-letter queue. A growing number is the first warning sign. */
    @Bean
    Object queueGauges(MeterRegistry registry, AmqpAdmin admin) {
        for (String q : new String[]{"alerts.events", "alerts.events.dlq"}) {
            Gauge.builder("stocksafe.queue.lag", () -> {
                try {
                    QueueInformation info = admin.getQueueInfo(q);
                    return info == null ? 0 : info.getMessageCount();
                } catch (Exception e) {
                    return -1;
                }
            }).tag("queue", q).register(registry);
        }
        return new Object();
    }
}
