package com.stocksafe.core.billing;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.outbox.OutboxService;
import com.stocksafe.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upgrade saga (each step is its own transaction, so a failure can always be compensated):
 *   STARTED -> PAYMENT_CONFIRMED -> PLAN_APPLIED -> COMPLETED
 *   any failure after payment -> COMPENSATING -> cancel the subscription, back to Free -> COMPENSATED
 */
@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RazorpayClient razorpay;
    private final OutboxService outbox;
    private final AuditService audit;
    private final int trialDays;
    private final int graceDays;

    /** Test hook: makes the "apply plan" step fail so we can see the compensation work. */
    public volatile boolean failApplyForTests = false;

    public BillingService(JdbcTemplate jdbc, TransactionTemplate tx, RazorpayClient razorpay, OutboxService outbox,
                          AuditService audit, @Value("${stocksafe.billing.trial-days}") int trialDays,
                          @Value("${stocksafe.billing.grace-days}") int graceDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.razorpay = razorpay;
        this.outbox = outbox;
        this.audit = audit;
        this.trialDays = trialDays;
        this.graceDays = graceDays;
    }

    // ---------- reads ----------

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", jdbc.queryForObject("select plan_code from tenants", String.class));
        out.put("mock", razorpay.isMock());
        out.put("trialAvailable", !trialUsed());
        out.put("subscription", first(jdbc.queryForList("""
                select status, razorpay_subscription_id, trial_ends_at, current_period_end, grace_ends_at
                from subscriptions order by created_at desc limit 1""")));
        out.put("lastSaga", first(jdbc.queryForList(
                "select id, state, error, updated_at from upgrade_sagas order by created_at desc limit 1")));
        out.put("invoices", jdbc.queryForList(
                "select amount_inr, status, created_at from invoices order by created_at desc limit 20"));
        return out;
    }

    public List<Map<String, Object>> plans() {
        return jdbc.queryForList("select code, name, max_shops, max_products, forecasting, whatsapp_alerts, "
                + "price_inr_month from plans order by price_inr_month");
    }

    // ---------- upgrade ----------

    public Map<String, Object> startUpgrade() {
        if ("pro".equals(jdbc.queryForObject("select plan_code from tenants", String.class))) {
            throw new ApiException(HttpStatus.CONFLICT, "Already on Pro");
        }
        Integer open = jdbc.queryForObject(
                "select count(*) from upgrade_sagas where state in ('STARTED','PAYMENT_CONFIRMED','COMPENSATING')",
                Integer.class);
        if (open != null && open > 0) throw new ApiException(HttpStatus.CONFLICT, "An upgrade is already in progress");

        UUID tenant = TenantContext.require();
        boolean trial = !trialUsed();
        RazorpayClient.Sub sub = razorpay.createSubscription(tenant, trial ? trialDays : 0);
        UUID sagaId = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("insert into subscriptions(id, tenant_id, plan_code, status, razorpay_subscription_id) "
                        + "values (?,?, 'pro', 'PENDING', ?)", UUID.randomUUID(), tenant, sub.id());
                jdbc.update("insert into upgrade_sagas(id, tenant_id, target_plan, state, razorpay_subscription_id) "
                        + "values (?,?, 'pro', 'STARTED', ?)", sagaId, tenant, sub.id());
            });
        } catch (RuntimeException e) {
            safeCancel(sub.id());       // do not leave a paid-for subscription we do not know about
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sagaId", sagaId);
        out.put("subscriptionId", sub.id());
        out.put("checkoutUrl", sub.checkoutUrl());
        out.put("trial", trial);
        return out;
    }

    // ---------- downgrade ----------

    public void downgrade() {
        Map<String, Object> u = jdbc.queryForMap("""
                select coalesce((select value from usage_counters where metric = 'shops'), 0) as shops,
                       coalesce((select value from usage_counters where metric = 'products'), 0) as products,
                       (select max_shops from plans where code = 'free') as max_shops,
                       (select max_products from plans where code = 'free') as max_products""");
        if (((Number) u.get("shops")).longValue() > ((Number) u.get("max_shops")).longValue()
                || ((Number) u.get("products")).longValue() > ((Number) u.get("max_products")).longValue()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Too much data for the Free plan. Free allows 1 shop and 200 products. Remove some first.");
        }
        List<String> subs = jdbc.queryForList("select razorpay_subscription_id from subscriptions "
                + "where status in ('PENDING','TRIALING','ACTIVE','PAST_DUE')", String.class);
        subs.forEach(this::safeCancel);
        tx.executeWithoutResult(s -> toFree());
        audit.log("PLAN_DOWNGRADED", "tenant", TenantContext.require(), Map.of());
    }

    // ---------- webhooks ----------

    public void onWebhook(String eventId, String event, String subId, Long amountPaise, String paymentId) {
        if (subId == null) return;
        Integer seen = jdbc.queryForObject("select count(*) from webhook_events where id = ?", Integer.class, eventId);
        if (seen != null && seen > 0) return;         // retried delivery: already handled
        UUID tenant = jdbc.queryForList("select billing_tenant_by_subscription(?)", UUID.class, subId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (tenant == null) {
            log.warn("Webhook {} for unknown subscription {}", event, subId);
            return;
        }
        TenantContext.callAs(tenant, () -> {
            switch (event) {
                case "subscription.authenticated", "subscription.activated", "subscription.charged" ->
                        confirmPayment(subId, event, amountPaise, paymentId);
                case "subscription.halted", "payment.failed", "subscription.pending" -> markPastDue(subId);
                case "subscription.cancelled", "subscription.completed" -> tx.executeWithoutResult(s -> toFree());
                default -> log.info("Ignoring webhook {}", event);
            }
            // Recorded last: if handling threw, Razorpay retries and we try again.
            jdbc.update("insert into webhook_events(id, event) values (?,?) on conflict do nothing", eventId, event);
            return null;
        });
    }

    private void confirmPayment(String subId, String event, Long amountPaise, String paymentId) {
        List<Map<String, Object>> sagas = jdbc.queryForList(
                "select id, state from upgrade_sagas where razorpay_subscription_id = ? order by created_at desc limit 1", subId);
        boolean starting = !sagas.isEmpty() && "STARTED".equals(sagas.get(0).get("state"));
        if (!starting) {                       // a renewal or a trial turning into paid
            tx.executeWithoutResult(s -> {
                jdbc.update("update subscriptions set status = 'ACTIVE', grace_ends_at = null, "
                        + "current_period_end = now() + interval '1 month', updated_at = now() "
                        + "where razorpay_subscription_id = ? and status <> 'CANCELLED'", subId);
                recordInvoice(amountPaise, paymentId);
            });
            return;
        }
        UUID sagaId = (UUID) sagas.get(0).get("id");
        setSaga(sagaId, "PAYMENT_CONFIRMED", null);
        try {
            tx.executeWithoutResult(s -> applyPlan(subId, event));
            setSaga(sagaId, "PLAN_APPLIED", null);
            tx.executeWithoutResult(s -> refreshLimits());
            if (!event.equals("subscription.authenticated")) {     // a trial start is not a payment
                tx.executeWithoutResult(s -> recordInvoice(amountPaise, paymentId));
            }
            setSaga(sagaId, "COMPLETED", null);
        } catch (RuntimeException e) {
            compensate(sagaId, subId, e);
        }
    }

    private void applyPlan(String subId, String event) {
        if (failApplyForTests) throw new IllegalStateException("simulated failure while applying plan");
        boolean trial = event.equals("subscription.authenticated");
        jdbc.update("update tenants set plan_code = 'pro'");                       // RLS: only my tenant
        jdbc.update("update subscriptions set status = ?, trial_used = (trial_used or ?), "
                + "trial_ends_at = case when ? then now() + make_interval(days => ?) else trial_ends_at end, "
                + "updated_at = now() where razorpay_subscription_id = ?",
                trial ? "TRIALING" : "ACTIVE", trial, trial, trialDays, subId);
        outbox.publish("tenant.plan_changed", Map.of("plan", "pro"));
    }

    /** Limits are read from the plan on every check, so they follow the plan automatically. We make sure counters exist. */
    private void refreshLimits() {
        UUID t = TenantContext.require();
        jdbc.update("insert into usage_counters(tenant_id, metric, value) values (?, 'shops', 0), (?, 'products', 0) "
                + "on conflict do nothing", t, t);
        audit.log("PLAN_UPGRADED", "tenant", t, Map.of("plan", "pro"));
    }

    private void compensate(UUID sagaId, String subId, RuntimeException cause) {
        log.error("Upgrade saga {} failed, compensating", sagaId, cause);
        setSaga(sagaId, "COMPENSATING", cause.getMessage());
        safeCancel(subId);                                    // stop billing the customer
        try {
            tx.executeWithoutResult(s -> toFree());           // back to a known state
        } catch (RuntimeException e) {
            log.error("Compensation could not reset plan for saga {}", sagaId, e);
        }
        setSaga(sagaId, "COMPENSATED", cause.getMessage());
    }

    private void markPastDue(String subId) {
        tx.executeWithoutResult(s -> jdbc.update("""
                update subscriptions set status = 'PAST_DUE',
                  grace_ends_at = coalesce(grace_ends_at, now() + make_interval(days => ?)), updated_at = now()
                where razorpay_subscription_id = ? and status in ('ACTIVE','TRIALING','PAST_DUE')""", graceDays, subId));
    }

    private void toFree() {
        jdbc.update("update tenants set plan_code = 'free'");
        jdbc.update("update subscriptions set status = 'CANCELLED', updated_at = now() "
                + "where status in ('PENDING','TRIALING','ACTIVE','PAST_DUE')");
        outbox.publish("tenant.plan_changed", Map.of("plan", "free"));
    }

    private void recordInvoice(Long amountPaise, String paymentId) {
        if (amountPaise == null || paymentId == null) return;
        jdbc.update("insert into invoices(id, tenant_id, razorpay_payment_id, amount_inr, status) "
                + "values (?,?,?,?, 'PAID') on conflict do nothing",
                UUID.randomUUID(), TenantContext.require(), paymentId, amountPaise / 100.0);
    }

    // ---------- grace period ----------

    /** A failed payment keeps Pro for graceDays. After that the tenant goes back to Free. */
    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "core-billing-grace", lockAtMostFor = "PT10M")
    public void expireGrace() {
        List<UUID> tenants = jdbc.queryForList("select tenant_id from billing_expired_grace()", UUID.class);
        for (UUID t : tenants) {
            TenantContext.callAs(t, () -> {
                jdbc.queryForList("select razorpay_subscription_id from subscriptions where status = 'PAST_DUE'",
                        String.class).forEach(this::safeCancel);
                tx.executeWithoutResult(s -> toFree());
                return null;
            });
        }
    }

    // ---------- helpers ----------

    public String currentSubscriptionId() {
        List<String> ids = jdbc.queryForList("select razorpay_subscription_id from subscriptions "
                + "where status in ('PENDING','TRIALING','ACTIVE','PAST_DUE') order by created_at desc limit 1", String.class);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "No subscription yet. Start an upgrade first.");
        return ids.get(0);
    }

    private boolean trialUsed() {
        Integer n = jdbc.queryForObject("select count(*) from subscriptions where trial_used", Integer.class);
        return n != null && n > 0;
    }

    private void setSaga(UUID id, String state, String error) {
        tx.executeWithoutResult(s -> jdbc.update(
                "update upgrade_sagas set state = ?, error = coalesce(?, error), updated_at = now() where id = ?",
                state, error, id));
    }

    private void safeCancel(String subId) {
        try {
            razorpay.cancelSubscription(subId);
        } catch (RuntimeException e) {
            log.error("Could not cancel subscription {} at the provider", subId, e);
        }
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
