package com.stocksafe.core.me;

import com.stocksafe.core.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Who am I, what plan, how much used." The React app calls this first. */
@RestController
public class MeController {
    private final JdbcTemplate jdbc;

    public MeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthPrincipal principal) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT t.name AS tenant_name, t.plan_code, p.max_shops, p.max_products,
                  COALESCE((SELECT value FROM usage_counters WHERE tenant_id = t.id AND metric = 'shops'), 0) AS shops_used,
                  COALESCE((SELECT value FROM usage_counters WHERE tenant_id = t.id AND metric = 'products'), 0) AS products_used
                FROM tenants t JOIN plans p ON p.code = t.plan_code""");   // RLS leaves only my tenant

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", principal.userId());
        out.put("tenantId", principal.tenantId());
        out.put("role", principal.role());
        out.put("tenantName", row.get("tenant_name"));
        out.put("plan", row.get("plan_code"));
        out.put("usage", Map.of(
                "shops", Map.of("used", row.get("shops_used"), "limit", row.get("max_shops") == null ? "unlimited" : row.get("max_shops")),
                "products", Map.of("used", row.get("products_used"), "limit", row.get("max_products") == null ? "unlimited" : row.get("max_products"))));
        return out;
    }
}
