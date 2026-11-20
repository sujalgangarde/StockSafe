package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocksafe.core.billing.BillingService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class BillingSagaTest extends AbstractIntegrationTest {

    @Autowired BillingService billing;

    @AfterEach
    void reset() { billing.failApplyForTests = false; }

    private ResponseEntity<Map> call(HttpMethod m, String url, String token, Object body) {
        return rest.exchange(url, m, new HttpEntity<>(body, auth(token)), Map.class);
    }

    private Map me(String token) { return call(HttpMethod.GET, "/api/v1/me", token, null).getBody(); }

    @Test
    void upgrade_with_trial_then_downgrade() {
        Map a = signup("Billing Store");
        String t = token(a);
        assertThat(call(HttpMethod.POST, "/api/v1/billing/upgrade", t, null).getStatusCode().value()).isEqualTo(200);

        // Payment method confirmed -> trial starts, plan becomes Pro
        call(HttpMethod.POST, "/api/v1/billing/dev/simulate", t, Map.of("event", "subscription.authenticated"));
        assertThat(me(t).get("plan")).isEqualTo("pro");
        Map billing = call(HttpMethod.GET, "/api/v1/billing", t, null).getBody();
        assertThat(((Map) billing.get("subscription")).get("status")).isEqualTo("TRIALING");
        assertThat(((Map) billing.get("lastSaga")).get("state")).isEqualTo("COMPLETED");
        assertThat(((Map) ((Map) me(t).get("usage")).get("shops")).get("limit")).isEqualTo(5);

        // First real charge -> ACTIVE and an invoice
        call(HttpMethod.POST, "/api/v1/billing/dev/simulate", t, Map.of("event", "subscription.charged"));
        billing = call(HttpMethod.GET, "/api/v1/billing", t, null).getBody();
        assertThat(((Map) billing.get("subscription")).get("status")).isEqualTo("ACTIVE");
        assertThat((java.util.List) billing.get("invoices")).hasSize(1);

        // Payment fails -> grace period, still Pro
        call(HttpMethod.POST, "/api/v1/billing/dev/simulate", t, Map.of("event", "payment.failed"));
        billing = call(HttpMethod.GET, "/api/v1/billing", t, null).getBody();
        assertThat(((Map) billing.get("subscription")).get("status")).isEqualTo("PAST_DUE");
        assertThat(((Map) billing.get("subscription")).get("grace_ends_at")).isNotNull();
        assertThat(me(t).get("plan")).isEqualTo("pro");

        // Downgrade
        assertThat(call(HttpMethod.POST, "/api/v1/billing/downgrade", t, null).getStatusCode().value()).isEqualTo(204);
        assertThat(me(t).get("plan")).isEqualTo("free");
    }

    @Test
    void failure_after_payment_is_compensated() {
        Map a = signup("Saga Fail");
        String t = token(a);
        billing.failApplyForTests = true;
        call(HttpMethod.POST, "/api/v1/billing/upgrade", t, null);
        call(HttpMethod.POST, "/api/v1/billing/dev/simulate", t, Map.of("event", "subscription.authenticated"));

        Map b = call(HttpMethod.GET, "/api/v1/billing", t, null).getBody();
        assertThat(((Map) b.get("lastSaga")).get("state")).isEqualTo("COMPENSATED");
        assertThat(((Map) b.get("subscription")).get("status")).isEqualTo("CANCELLED");
        assertThat(me(t).get("plan")).isEqualTo("free");
    }

    @Test
    void webhook_needs_a_valid_signature() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Razorpay-Signature", "nope");
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange("/api/v1/billing/webhook", HttpMethod.POST,
                new HttpEntity<>("{\"event\":\"subscription.charged\"}", h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void staff_cannot_upgrade() {
        Map a = signup("Role Billing");
        String staff = token(a);   // owner token, then check a STAFF token below
        String staffToken = new com.stocksafe.core.security.JwtService(
                "change-me-change-me-change-me-32bytes!!", 15)
                .createAccessToken(java.util.UUID.randomUUID(), tenantId(a), "STAFF");
        assertThat(call(HttpMethod.POST, "/api/v1/billing/upgrade", staffToken, null).getStatusCode().value()).isEqualTo(403);
        assertThat(staff).isNotNull();
    }
}
