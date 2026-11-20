package com.stocksafe.alerts.detection;

import com.stocksafe.alerts.notify.NotificationService;
import com.stocksafe.alerts.outbox.AlertOutbox;
import com.stocksafe.alerts.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides when to alert. Runs on every event (instant) and from a scheduled scan
 * (because stock gets closer to expiry just by time passing). Always in a tenant context.
 */
@Service
public class DetectionService {
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AlertOutbox outbox;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public DetectionService(JdbcTemplate jdbc, NotificationService notifications, AlertOutbox outbox,
                            MeterRegistry metrics, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.outbox = outbox;
        this.metrics = metrics;
        this.tx = tx;
    }

    public void evaluateBatch(UUID batchId, Instant eventTime) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select shop_id, product_id, product_name, batch_no, expiry_date, qty
                from batch_snapshots where batch_id = ?""", batchId);
        if (rows.isEmpty()) return;
        Map<String, Object> b = rows.get(0);
        UUID shopId = (UUID) b.get("shop_id");
        Integer days = jdbc.queryForObject("""
                select max(threshold_days) from alert_rules
                where type = 'NEAR_EXPIRY' and enabled and (shop_id is null or shop_id = ?)""", Integer.class, shopId);
        Date expiry = (Date) b.get("expiry_date");
        int qty = ((Number) b.get("qty")).intValue();
        String key = "NEAR_EXPIRY:" + batchId;
        boolean due = days != null && expiry != null && qty > 0
                && !expiry.toLocalDate().isAfter(LocalDate.now().plusDays(days));
        if (!due) {
            resolve(key);
            return;
        }
        long left = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiry.toLocalDate());
        String msg = b.get("product_name") + " (batch " + b.get("batch_no") + "): " + qty + " left, "
                + (left < 0 ? "expired " + (-left) + " days ago" : left == 0 ? "expires today" : "expires in " + left + " days");
        raise(shopId, "NEAR_EXPIRY", (UUID) b.get("product_id"), batchId, msg, key, eventTime);
    }

    public void evaluateProduct(UUID productId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.shop_id, p.name, p.reorder_level,
                  coalesce((select sum(b.qty) from batch_snapshots b where b.product_id = p.product_id
                            and (b.expiry_date is null or b.expiry_date >= current_date)), 0) as sellable
                from product_snapshots p where p.product_id = ?""", productId);
        if (rows.isEmpty()) return;
        Map<String, Object> p = rows.get(0);
        UUID shopId = (UUID) p.get("shop_id");
        int reorder = ((Number) p.get("reorder_level")).intValue();
        long sellable = ((Number) p.get("sellable")).longValue();
        Integer rule = jdbc.queryForObject("""
                select count(*) from alert_rules where type = 'LOW_STOCK' and enabled
                and (shop_id is null or shop_id = ?)""", Integer.class, shopId);
        String key = "LOW_STOCK:" + productId;
        if (rule != null && rule > 0 && reorder > 0 && sellable <= reorder) {
            raise(shopId, "LOW_STOCK", productId, null,
                    p.get("name") + ": only " + sellable + " left (reorder level " + reorder + ")", key, null);
        } else {
            resolve(key);
        }
    }

    public void resolveProduct(UUID productId) {
        resolve("LOW_STOCK:" + productId);
        for (UUID b : jdbc.queryForList("select id from alerts where product_id = ? and status = 'OPEN'", UUID.class, productId)) {
            jdbc.update("update alerts set status = 'RESOLVED', resolved_at = now() where id = ?", b);
        }
    }

    /** Scheduled: re-check every tenant. Time passing can make a batch "near expiry" with no event at all. */
    public void scanAll(List<UUID> tenants) {
        for (UUID t : tenants) {
            TenantContext.callAs(t, () -> tx.execute(s -> {
                for (UUID b : jdbc.queryForList("select batch_id from batch_snapshots where qty > 0 "
                        + "and expiry_date is not null and expiry_date <= current_date + 400", UUID.class)) {
                    evaluateBatch(b, null);
                }
                for (UUID p : jdbc.queryForList("select product_id from product_snapshots where reorder_level > 0", UUID.class)) {
                    evaluateProduct(p);
                }
                return null;
            }));
        }
    }

    private void raise(UUID shopId, String type, UUID productId, UUID batchId, String message, String key, Instant eventTime) {
        UUID id = UUID.randomUUID();
        int created = jdbc.update("""
                insert into alerts(id, tenant_id, shop_id, type, product_id, batch_id, message, dedupe_key)
                values (?,?,?,?,?,?,?,?)
                on conflict (tenant_id, dedupe_key) where status = 'OPEN' do nothing""",
                id, TenantContext.require(), shopId, type, productId, batchId, message, key);
        if (created == 0) return;                      // already open: do not alert twice
        notifications.enqueue(id, type, message);
        Map<String, Object> data = new HashMap<>();
        data.put("alertId", id.toString());
        data.put("shopId", shopId.toString());
        data.put("type", type);
        data.put("message", message);
        data.put("productId", productId == null ? null : productId.toString());
        outbox.publish("alert.created", data);
        if (eventTime != null) {
            Timer.builder("stocksafe.alert.latency").description("event time to alert saved").register(metrics)
                    .record(Duration.between(eventTime, Instant.now()));
        }
    }

    private void resolve(String key) {
        for (Map<String, Object> r : jdbc.queryForList("update alerts set status = 'RESOLVED', resolved_at = now() "
                + "where dedupe_key = ? and status = 'OPEN' returning id, shop_id", key)) {
            outbox.publish("alert.resolved", Map.of("alertId", r.get("id").toString(), "shopId", r.get("shop_id").toString()));
        }
    }
}
