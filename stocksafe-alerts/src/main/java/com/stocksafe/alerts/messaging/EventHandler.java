package com.stocksafe.alerts.messaging;

import com.stocksafe.alerts.common.Json;
import com.stocksafe.alerts.detection.DetectionService;
import com.stocksafe.alerts.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one event. Idempotent: the event id is recorded in the SAME transaction as its effects,
 * so a redelivered event is ignored and a crash half-way leaves nothing behind.
 */
@Component
public class EventHandler {
    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DetectionService detection;
    private final Counter duplicates;
    private final Counter processed;

    public EventHandler(JdbcTemplate jdbc, TransactionTemplate tx, DetectionService detection, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.detection = detection;
        this.duplicates = Counter.builder("stocksafe.events.duplicates").register(metrics);
        this.processed = Counter.builder("stocksafe.events.processed").register(metrics);
    }

    @SuppressWarnings("unchecked")
    public void handle(String body) {
        Map<String, Object> env = Json.readMap(body);
        UUID eventId = UUID.fromString((String) env.get("eventId"));
        UUID tenantId = UUID.fromString((String) env.get("tenantId"));
        String type = (String) env.get("type");
        long seq = ((Number) env.get("seq")).longValue();
        Instant occurred = env.get("occurredAt") == null ? null : Instant.parse((String) env.get("occurredAt"));
        Map<String, Object> data = (Map<String, Object>) env.get("data");
        MDC.put("correlationId", String.valueOf(env.get("correlationId")));
        MDC.put("tenantId", tenantId.toString());
        try {
            TenantContext.callAs(tenantId, () -> tx.execute(s -> {
                int fresh = jdbc.update("insert into processed_events(consumer, event_id) values ('alerts', ?) "
                        + "on conflict do nothing", eventId);
                if (fresh == 0) {
                    duplicates.increment();
                    log.info("Duplicate event {} ignored", eventId);
                    return null;
                }
                switch (type) {
                    case "tenant.created" -> tenantCreated(tenantId, data);
                    case "tenant.plan_changed" -> jdbc.update("update tenant_plans set plan_code = ? where tenant_id = ?",
                            data.get("plan"), tenantId);
                    case "product.upserted" -> productUpserted(tenantId, data, seq);
                    case "batch.changed" -> batchChanged(tenantId, data, seq, occurred);
                    default -> log.info("Ignoring event type {}", type);
                }
                processed.increment();
                return null;
            }));
        } finally {
            MDC.remove("correlationId");
            MDC.remove("tenantId");
        }
    }

    private void tenantCreated(UUID tenantId, Map<String, Object> d) {
        jdbc.update("insert into tenant_plans(tenant_id, plan_code, owner_email) values (?,?,?) "
                + "on conflict (tenant_id) do update set owner_email = excluded.owner_email",
                tenantId, d.getOrDefault("plan", "free"), d.get("ownerEmail"));
        Integer rules = jdbc.queryForObject("select count(*) from alert_rules", Integer.class);
        if (rules != null && rules == 0) {          // sensible defaults for every new shop owner
            jdbc.update("insert into alert_rules(id, tenant_id, type, threshold_days) values (?,?, 'NEAR_EXPIRY', 30)",
                    UUID.randomUUID(), tenantId);
            jdbc.update("insert into alert_rules(id, tenant_id, type) values (?,?, 'LOW_STOCK')",
                    UUID.randomUUID(), tenantId);
        }
    }

    private void productUpserted(UUID tenantId, Map<String, Object> d, long seq) {
        UUID productId = UUID.fromString((String) d.get("productId"));
        if (Boolean.TRUE.equals(d.get("deleted"))) {
            jdbc.update("delete from product_snapshots where product_id = ?", productId);
            jdbc.update("delete from batch_snapshots where product_id = ?", productId);
            detection.resolveProduct(productId);
            return;
        }
        jdbc.update("""
                insert into product_snapshots(product_id, tenant_id, shop_id, name, reorder_level, last_seq)
                values (?,?,?,?,?,?)
                on conflict (product_id) do update set name = excluded.name, reorder_level = excluded.reorder_level,
                  last_seq = excluded.last_seq where product_snapshots.last_seq < excluded.last_seq""",
                productId, tenantId, UUID.fromString((String) d.get("shopId")), d.get("name"),
                ((Number) d.get("reorderLevel")).intValue(), seq);
        detection.evaluateProduct(productId);
    }

    private void batchChanged(UUID tenantId, Map<String, Object> d, long seq, Instant occurred) {
        UUID batchId = UUID.fromString((String) d.get("batchId"));
        UUID productId = UUID.fromString((String) d.get("productId"));
        UUID shopId = UUID.fromString((String) d.get("shopId"));
        // keep a product row even if product.upserted has not arrived yet (seq 0 loses to any real one)
        jdbc.update("insert into product_snapshots(product_id, tenant_id, shop_id, name, reorder_level, last_seq) "
                        + "values (?,?,?,?,?,0) on conflict (product_id) do nothing",
                productId, tenantId, shopId, d.get("productName"), ((Number) d.get("reorderLevel")).intValue());
        jdbc.update("""
                insert into batch_snapshots(batch_id, tenant_id, shop_id, product_id, product_name, batch_no, expiry_date, qty, last_seq)
                values (?,?,?,?,?,?,?::date,?,?)
                on conflict (batch_id) do update set product_name = excluded.product_name,
                  expiry_date = excluded.expiry_date, qty = excluded.qty, last_seq = excluded.last_seq, updated_at = now()
                where batch_snapshots.last_seq < excluded.last_seq""",
                batchId, tenantId, shopId, productId, d.get("productName"), d.get("batchNo"), d.get("expiryDate"),
                ((Number) d.get("qty")).intValue(), seq);
        if ("SALE".equals(d.get("movementType"))) {         // feed the forecast
            String day = occurred == null ? java.time.LocalDate.now().toString() : occurred.toString().substring(0, 10);
            jdbc.update("""
                    insert into sales_daily(tenant_id, shop_id, product_id, day, qty) values (?,?,?,?::date,?)
                    on conflict (tenant_id, product_id, day) do update set qty = sales_daily.qty + excluded.qty""",
                    tenantId, shopId, productId, day, -((Number) d.get("delta")).intValue());
        }
        detection.evaluateBatch(batchId, occurred);
        detection.evaluateProduct(productId);
    }
}
