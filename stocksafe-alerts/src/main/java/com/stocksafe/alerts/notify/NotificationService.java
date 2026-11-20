package com.stocksafe.alerts.notify;

import com.stocksafe.alerts.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Queues messages. Email for everyone, WhatsApp on Pro. The dispatcher sends them later (with retries). */
@Service
public class NotificationService {
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID alertId, String type, String message) {
        UUID tenant = TenantContext.require();
        List<Map<String, Object>> plan = jdbc.queryForList(
                "select plan_code, owner_email from tenant_plans where tenant_id = ?", tenant);
        if (plan.isEmpty() || plan.get(0).get("owner_email") == null) return;
        String email = (String) plan.get(0).get("owner_email");
        String subject = (type.equals("LOW_STOCK") ? "Low stock: " : "Expiry alert: ") + message;
        insert(tenant, alertId, "EMAIL", email, subject, message);
        if ("pro".equals(plan.get(0).get("plan_code"))) {
            insert(tenant, alertId, "WHATSAPP", "whatsapp:" + email, subject, message);
        }
    }

    public void enqueueDigest(String body) {
        UUID tenant = TenantContext.require();
        List<String> email = jdbc.queryForList("select owner_email from tenant_plans where tenant_id = ?", String.class, tenant);
        if (email.isEmpty() || email.get(0) == null) return;
        insert(tenant, null, "EMAIL", email.get(0), "Your StockSafe daily summary", body);
    }

    private void insert(UUID tenant, UUID alertId, String channel, String recipient, String subject, String body) {
        jdbc.update("insert into notifications(id, tenant_id, alert_id, channel, recipient, subject, body) "
                + "values (?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, alertId, channel, recipient, subject, body);
    }
}
