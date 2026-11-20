package com.stocksafe.core.billing;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.common.Json;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingService service;
    private final RazorpayClient razorpay;
    private final String webhookSecret;

    public BillingController(BillingService service, RazorpayClient razorpay,
                             @Value("${stocksafe.billing.razorpay-webhook-secret}") String webhookSecret) {
        this.service = service;
        this.razorpay = razorpay;
        this.webhookSecret = webhookSecret;
    }

    public record SimulateRequest(@NotBlank String event) {}

    @GetMapping
    public Map<String, Object> overview() { return service.overview(); }

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() { return service.plans(); }

    @PostMapping("/upgrade")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> upgrade() { return service.startUpgrade(); }

    @PostMapping("/downgrade")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void downgrade() { service.downgrade(); }

    /** Local testing only: pretends Razorpay sent a webhook for my subscription. */
    @PostMapping("/dev/simulate")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, String> simulate(@Valid @RequestBody SimulateRequest r) {
        if (!razorpay.isMock()) throw new ApiException(HttpStatus.NOT_FOUND, "Not available");
        service.onWebhook("evt_mock_" + UUID.randomUUID(), r.event(), service.currentSubscriptionId(),
                49900L, "pay_mock_" + UUID.randomUUID());
        return Map.of("ok", "true");
    }

    /** Razorpay calls this. Public URL, so the signature is the only proof it is really Razorpay. */
    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public Map<String, String> webhook(@RequestBody String body,
                                       @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        if (signature == null || !MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bad signature");
        }
        Map<String, Object> root = Json.readMap(body);
        Map<String, Object> payload = (Map<String, Object>) root.getOrDefault("payload", Map.of());
        String subId = entityId(payload, "subscription");
        Map<String, Object> pay = (Map<String, Object>) ((Map<String, Object>) payload.getOrDefault("payment", Map.of()))
                .getOrDefault("entity", Map.of());
        Long amount = pay.get("amount") instanceof Number n ? n.longValue() : null;
        String id = eventId != null ? eventId : UUID.nameUUIDFromBytes(body.getBytes(StandardCharsets.UTF_8)).toString();
        service.onWebhook(id, (String) root.get("event"), subId, amount, (String) pay.get("id"));
        return Map.of("status", "ok");
    }

    @SuppressWarnings("unchecked")
    private static String entityId(Map<String, Object> payload, String key) {
        Object o = payload.get(key);
        if (!(o instanceof Map<?, ?> m) || !(((Map<String, Object>) m).get("entity") instanceof Map<?, ?> e)) return null;
        return (String) ((Map<String, Object>) e).get("id");
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
