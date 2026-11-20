package com.stocksafe.core.admin;

import com.stocksafe.core.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Platform admin (you, the SaaS owner). Protected by X-Admin-Key. Empty key in config = switched off. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final String adminKey;

    public AdminController(JdbcTemplate jdbc, @Value("${stocksafe.admin.key}") String adminKey) {
        this.jdbc = jdbc;
        this.adminKey = adminKey;
    }

    private void check(String key) {
        if (adminKey.isBlank() || key == null || !MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        check(key);
        return jdbc.queryForList("select * from admin_tenants()");
    }

    @GetMapping("/health")
    public Map<String, Object> health(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        check(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", jdbc.queryForObject("select 'ok'", String.class));
        out.put("outboxPending", jdbc.queryForObject(
                "select count(*) from outbox_events where published_at is null", Long.class));
        out.put("oldestPendingSeconds", jdbc.queryForObject(
                "select coalesce(extract(epoch from now() - min(created_at)), 0)::bigint "
                        + "from outbox_events where published_at is null", Long.class));
        return out;
    }
}
