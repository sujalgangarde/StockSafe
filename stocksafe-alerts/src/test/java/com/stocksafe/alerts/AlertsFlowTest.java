package com.stocksafe.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocksafe.alerts.messaging.EventHandler;
import com.stocksafe.alerts.notify.NotificationDispatcher;
import com.stocksafe.alerts.notify.NotificationSender;
import com.stocksafe.alerts.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AlertsFlowTest extends AbstractAlertsTest {

    @Autowired EventHandler handler;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired NotificationSender sender;

    @AfterEach
    void reset() { sender.setFailRate(0); }

    private UUID newTenant() {
        UUID t = UUID.randomUUID();
        handler.handle(event(UUID.randomUUID(), t, "tenant.created", nextSeq(),
                Map.of("name", "Shop", "ownerEmail", "owner@test.com", "plan", "free")));
        return t;
    }

    private int count(UUID tenant, String sql, Object... args) {
        return TenantContext.callAs(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private void batch(UUID tenant, UUID shop, UUID product, UUID batch, int qty, LocalDate expiry, int reorder, long seq) {
        handler.handle(event(UUID.randomUUID(), tenant, "batch.changed", seq,
                batchData(shop, product, batch, qty, expiry, reorder, "RECEIVE", qty)));
    }

    @Test
    void new_tenant_gets_default_rules() {
        UUID t = newTenant();
        assertThat(count(t, "select count(*) from alert_rules")).isEqualTo(2);
    }

    @Test
    void near_expiry_alert_is_created_once_even_if_the_event_is_delivered_twice() {
        UUID t = newTenant();
        UUID shop = UUID.randomUUID(), product = UUID.randomUUID(), batch = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = event(eventId, t, "batch.changed", nextSeq(),
                batchData(shop, product, batch, 50, LocalDate.now().plusDays(10), 0, "RECEIVE", 50));

        handler.handle(json);
        handler.handle(json);                       // redelivery: must change nothing

        assertThat(count(t, "select count(*) from alerts where type = 'NEAR_EXPIRY' and status = 'OPEN'")).isEqualTo(1);
        assertThat(count(t, "select count(*) from notifications where channel = 'EMAIL'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where consumer = 'alerts' and event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from alert_outbox_events where tenant_id = ? and type = 'alert.created'",
                Integer.class, t)).isEqualTo(1);
    }

    @Test
    void far_expiry_does_not_alert() {
        UUID t = newTenant();
        batch(t, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 50, LocalDate.now().plusDays(200), 0, nextSeq());
        assertThat(count(t, "select count(*) from alerts")).isZero();
    }

    @Test
    void low_stock_alert_opens_and_resolves_when_restocked() {
        UUID t = newTenant();
        UUID shop = UUID.randomUUID(), product = UUID.randomUUID(), b = UUID.randomUUID();
        handler.handle(event(UUID.randomUUID(), t, "product.upserted", nextSeq(), Map.of(
                "productId", product.toString(), "shopId", shop.toString(), "name", "Cough Syrup",
                "reorderLevel", 5, "deleted", false)));
        assertThat(count(t, "select count(*) from alerts where type = 'LOW_STOCK' and status = 'OPEN'")).isEqualTo(1);

        batch(t, shop, product, b, 20, LocalDate.now().plusDays(300), 5, nextSeq());
        assertThat(count(t, "select count(*) from alerts where type = 'LOW_STOCK' and status = 'OPEN'")).isZero();
        assertThat(count(t, "select count(*) from alerts where type = 'LOW_STOCK' and status = 'RESOLVED'")).isEqualTo(1);
    }

    @Test
    void older_event_arriving_late_is_ignored() {
        UUID t = newTenant();
        UUID shop = UUID.randomUUID(), product = UUID.randomUUID(), b = UUID.randomUUID();
        long newer = nextSeq(), older = newer - 1;
        batch(t, shop, product, b, 20, LocalDate.now().plusDays(300), 0, newer);
        batch(t, shop, product, b, 1, LocalDate.now().plusDays(300), 0, older);
        assertThat(count(t, "select qty from batch_snapshots where batch_id = ?", b)).isEqualTo(20);
    }

    @Test
    void pro_plan_also_gets_whatsapp() {
        UUID t = newTenant();
        handler.handle(event(UUID.randomUUID(), t, "tenant.plan_changed", nextSeq(), Map.of("plan", "pro")));
        batch(t, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, LocalDate.now().plusDays(3), 0, nextSeq());
        assertThat(count(t, "select count(*) from notifications")).isEqualTo(2);
        assertThat(count(t, "select count(*) from notifications where channel = 'WHATSAPP'")).isEqualTo(1);
    }

    @Test
    void failed_notification_backs_off_then_succeeds() {
        UUID t = newTenant();
        batch(t, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, LocalDate.now().plusDays(3), 0, nextSeq());

        sender.setFailRate(1.0);
        dispatcher.dispatchOnce();
        assertThat(count(t, "select count(*) from notifications where status = 'PENDING' and attempts = 1 and last_error is not null"))
                .isEqualTo(1);

        sender.setFailRate(0);
        TenantContext.callAs(t, () -> jdbc.update("update notifications set next_attempt_at = now()"));
        dispatcher.dispatchOnce();
        assertThat(count(t, "select count(*) from notifications where status = 'SENT'")).isEqualTo(1);
    }

    @Test
    void tenant_cannot_see_other_tenants_alerts() {
        UUID a = newTenant(), b = newTenant();
        batch(a, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, LocalDate.now().plusDays(3), 0, nextSeq());
        assertThat(count(a, "select count(*) from alerts")).isEqualTo(1);
        assertThat(count(b, "select count(*) from alerts")).isZero();
        assertThat(count(null, "select count(*) from alerts")).isZero();
    }
}
