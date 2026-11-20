package com.stocksafe.core.outbox;

import com.stocksafe.core.common.Json;
import com.stocksafe.core.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Outbox pattern: the event is saved in the SAME transaction as the business change.
 * A relay sends it to RabbitMQ later. So a crash can never save data without its event.
 */
@Service
public class OutboxService {
    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void publish(String type, Map<String, Object> data) {
        jdbc.update("insert into outbox_events(id, tenant_id, type, payload, correlation_id) values (?,?,?,?::jsonb,?)",
                UUID.randomUUID(), TenantContext.require(), type, Json.write(data), MDC.get("correlationId"));
    }
}
