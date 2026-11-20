package com.stocksafe.alerts.api;

import com.stocksafe.alerts.common.ApiException;
import com.stocksafe.alerts.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Reorder suggestions. Pro plan only; the check is in code, not just in the UI. */
@RestController
public class ForecastController {
    private final JdbcTemplate jdbc;

    public ForecastController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/forecast")
    public List<Map<String, Object>> forecast(@RequestParam(required = false) UUID shopId) {
        List<String> plan = jdbc.queryForList("select plan_code from tenant_plans where tenant_id = ?",
                String.class, TenantContext.require());
        if (plan.isEmpty() || !"pro".equals(plan.get(0))) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Forecasting is a Pro feature. Upgrade to Pro.");
        }
        return jdbc.queryForList("select shop_id, product_id, product_name, avg_daily, stock, days_of_cover, "
                + "suggested_qty, computed_at from forecasts where suggested_qty > 0"
                + (shopId == null ? "" : " and shop_id = '" + shopId + "'") + " order by suggested_qty desc limit 200");
    }
}
