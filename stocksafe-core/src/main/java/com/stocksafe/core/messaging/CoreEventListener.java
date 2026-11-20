package com.stocksafe.core.messaging;

import com.stocksafe.core.common.Json;
import com.stocksafe.core.sse.SseHub;
import com.stocksafe.core.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Turns alert events from the alerts service into live updates for the shop's browsers. */
@Component
public class CoreEventListener {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SseHub sse;

    public CoreEventListener(JdbcTemplate jdbc, TransactionTemplate tx, SseHub sse) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sse = sse;
    }

    @RabbitListener(queues = RabbitConfig.ALERTS_QUEUE)
    public void onAlert(String body) {
        handle(body);
    }

    public void handle(String body) {
        Map<String, Object> env = Json.readMap(body);
        UUID eventId = UUID.fromString((String) env.get("eventId"));
        UUID tenantId = UUID.fromString((String) env.get("tenantId"));
        MDC.put("correlationId", String.valueOf(env.get("correlationId")));
        try {
            TenantContext.callAs(tenantId, () -> tx.execute(s -> {
                // Idempotent consumer: the same event id is processed once.
                int first = jdbc.update("insert into processed_events(consumer, event_id) values ('core-sse', ?) "
                        + "on conflict do nothing", eventId);
                if (first == 0) return null;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) env.get("data");
                sse.publish(UUID.fromString((String) data.get("shopId")), (String) env.get("type"), data);
                return null;
            }));
        } finally {
            MDC.remove("correlationId");
        }
    }
}
