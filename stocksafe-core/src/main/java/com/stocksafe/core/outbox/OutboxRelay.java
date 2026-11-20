package com.stocksafe.core.outbox;

import com.stocksafe.core.common.Json;
import com.stocksafe.core.messaging.RabbitConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends unpublished outbox rows to RabbitMQ, oldest first.
 * ShedLock: with many instances, only one relays at a time.
 * FOR UPDATE SKIP LOCKED + publisher confirms: a row is marked sent only after the broker confirms it.
 * If we crash after sending but before marking, the row is sent again. Consumers ignore duplicates.
 */
@Component
@ConditionalOnProperty(name = "stocksafe.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, RabbitTemplate rabbit, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        Gauge.builder("stocksafe.outbox.pending", this, r -> r.pending()).register(metrics);
    }

    double pending() {
        try {
            Long n = jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "core-outbox-relay", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
    public void relay() {
        try {
            tx.executeWithoutResult(s -> sendBatch());
        } catch (Exception e) {
            log.warn("Outbox relay failed, will retry: {}", e.toString());
        }
    }

    private void sendBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select seq, id, tenant_id, type, payload::text as payload, correlation_id,
                       to_char(created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') as occurred
                from outbox_events where published_at is null
                order by seq limit 100 for update skip locked""");
        if (rows.isEmpty()) return;
        rabbit.invoke(ops -> {
            for (Map<String, Object> r : rows) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", r.get("id").toString());
                envelope.put("tenantId", r.get("tenant_id").toString());
                envelope.put("type", r.get("type"));
                envelope.put("seq", r.get("seq"));
                envelope.put("occurredAt", r.get("occurred"));
                envelope.put("correlationId", r.get("correlation_id"));
                envelope.put("data", Json.readMap((String) r.get("payload")));
                Message m = MessageBuilder.withBody(Json.write(envelope).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(r.get("id").toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                ops.send(RabbitConfig.EXCHANGE, (String) r.get("type"), m);
            }
            ops.waitForConfirmsOrDie(10_000);
            return null;
        });
        for (Map<String, Object> r : rows) {
            jdbc.update("update outbox_events set published_at = now() where id = ?", (UUID) r.get("id"));
        }
    }
}
