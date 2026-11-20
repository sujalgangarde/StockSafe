package com.stocksafe.core.billing;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.common.Json;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Razorpay REST calls (test mode). In mock mode nothing leaves this machine. */
@Component
public class RazorpayClient {
    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    public record Sub(String id, String checkoutUrl) {}

    private final boolean mock;
    private final String planId;
    private final RestClient rest;

    public RazorpayClient(@Value("${stocksafe.billing.mock}") boolean mock,
                          @Value("${stocksafe.billing.razorpay-key-id}") String keyId,
                          @Value("${stocksafe.billing.razorpay-key-secret}") String secret,
                          @Value("${stocksafe.billing.razorpay-plan-pro}") String planId) {
        this.mock = mock;
        this.planId = planId;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);          // never hang on the payment provider
        f.setReadTimeout(10000);
        this.rest = RestClient.builder().baseUrl("https://api.razorpay.com").requestFactory(f)
                .defaultHeaders(h -> { if (!mock) h.setBasicAuth(keyId, secret); }).build();
    }

    public boolean isMock() { return mock; }

    public Sub createSubscription(UUID tenantId, int trialDays) {
        if (mock) return new Sub("sub_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), null);
        if (planId == null || planId.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing plan is not configured");
        Map<String, Object> body = new HashMap<>();
        body.put("plan_id", planId);
        body.put("total_count", 120);
        body.put("customer_notify", 1);
        body.put("notes", Map.of("tenantId", tenantId.toString()));
        if (trialDays > 0) body.put("start_at", Instant.now().plusSeconds(trialDays * 86400L).getEpochSecond());
        try {
            String res = rest.post().uri("/v1/subscriptions").contentType(MediaType.APPLICATION_JSON)
                    .body(Json.write(body)).retrieve().body(String.class);
            Map<String, Object> m = Json.readMap(res);
            return new Sub((String) m.get("id"), (String) m.get("short_url"));
        } catch (RuntimeException e) {
            log.error("Razorpay create failed", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Payment provider error. Please try again.");
        }
    }

    public void cancelSubscription(String subscriptionId) {
        if (mock) { log.info("MOCK cancel {}", subscriptionId); return; }
        rest.post().uri("/v1/subscriptions/{id}/cancel", subscriptionId).contentType(MediaType.APPLICATION_JSON)
                .body("{\"cancel_at_cycle_end\":0}").retrieve().toBodilessEntity();
    }
}
