package com.stocksafe.alerts.outbox;

import com.stocksafe.alerts.common.Json;
import com.stocksafe.alerts.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Same outbox pattern as core: the event is stored with the alert, sent later by the relay. */
@Service
public class AlertOutbox {
    private final JdbcTemplate jdbc;

    public AlertOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void publish(String type, Map<String, Object> data) {
        jdbc.update("insert into alert_outbox_events(id, tenant_id, type, payload, correlation_id) values (?,?,?,?::jsonb,?)",
                UUID.randomUUID(), TenantContext.require(), type, Json.write(data), MDC.get("correlationId"));
    }
}
