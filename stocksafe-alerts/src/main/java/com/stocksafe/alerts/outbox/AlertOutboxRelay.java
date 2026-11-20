package com.stocksafe.alerts.outbox;

import com.stocksafe.alerts.common.Json;
import com.stocksafe.alerts.messaging.RabbitConfig;
import java.nio.charset.StandardCharsets;
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

@Component
@ConditionalOnProperty(name = "stocksafe.relay.enabled", havingValue = "true", matchIfMissing = true)
public class AlertOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(AlertOutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;

    public AlertOutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "alerts-outbox-relay", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
    public void relay() {
        try {
            tx.executeWithoutResult(s -> sendBatch());
        } catch (Exception e) {
            log.warn("Alert outbox relay failed, will retry: {}", e.toString());
        }
    }

    private void sendBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select seq, id, tenant_id, type, payload::text as payload, correlation_id,
                       to_char(created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') as occurred
                from alert_outbox_events where published_at is null order by seq limit 100 for update skip locked""");
        if (rows.isEmpty()) return;
        rabbit.invoke(ops -> {
            for (Map<String, Object> r : rows) {
                Map<String, Object> env = new LinkedHashMap<>();
                env.put("eventId", r.get("id").toString());
                env.put("tenantId", r.get("tenant_id").toString());
                env.put("type", r.get("type"));
                env.put("seq", r.get("seq"));
                env.put("occurredAt", r.get("occurred"));
                env.put("correlationId", r.get("correlation_id"));
                env.put("data", Json.readMap((String) r.get("payload")));
                Message m = MessageBuilder.withBody(Json.write(env).getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json").setMessageId(r.get("id").toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                ops.send(RabbitConfig.EXCHANGE, (String) r.get("type"), m);
            }
            ops.waitForConfirmsOrDie(10_000);
            return null;
        });
        for (Map<String, Object> r : rows) {
            jdbc.update("update alert_outbox_events set published_at = now() where id = ?", (UUID) r.get("id"));
        }
    }
}
