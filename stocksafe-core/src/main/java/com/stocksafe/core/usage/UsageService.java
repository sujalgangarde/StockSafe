package com.stocksafe.core.usage;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Plan limits, enforced in code with one atomic SQL statement. */
@Service
public class UsageService {
    // Which plan column limits which metric. NULL means unlimited.
    private static final String LIMIT =
            "CASE u.metric WHEN 'shops' THEN p.max_shops WHEN 'products' THEN p.max_products END";

    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** +1 only if still under the limit. If two requests race, the DB lets only one win the last slot. */
    public void consumeOrThrow(String metric) {
        int rows = jdbc.update("""
                UPDATE usage_counters u
                SET value = u.value + 1, updated_at = now()
                FROM tenants t JOIN plans p ON p.code = t.plan_code
                WHERE t.id = u.tenant_id AND u.metric = ? AND u.tenant_id = ?
                  AND (%1$s IS NULL OR u.value < %1$s)
                """.formatted(LIMIT), metric, TenantContext.require());
        if (rows == 0) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED,
                    "Plan limit reached for " + metric + ". Upgrade to Pro.");
        }
    }

    public void release(String metric) {
        jdbc.update("UPDATE usage_counters SET value = GREATEST(value - 1, 0), updated_at = now() "
                + "WHERE tenant_id = ? AND metric = ?", TenantContext.require(), metric);
    }
}
