package com.stocksafe.alerts.api;

import com.stocksafe.alerts.common.ApiException;
import com.stocksafe.alerts.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alert-rules")
public class RuleController {
    public record RuleRequest(UUID shopId, @NotNull @Pattern(regexp = "NEAR_EXPIRY|LOW_STOCK") String type,
                              @Min(1) @Max(365) Integer thresholdDays, Boolean enabled) {}

    private final JdbcTemplate jdbc;

    public RuleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, shop_id, type, threshold_days, enabled from alert_rules order by created_at");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, UUID> create(@Valid @RequestBody RuleRequest r) {
        if (r.type().equals("NEAR_EXPIRY") && r.thresholdDays() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "thresholdDays is required for NEAR_EXPIRY");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into alert_rules(id, tenant_id, shop_id, type, threshold_days, enabled) values (?,?,?,?,?,?)",
                id, TenantContext.require(), r.shopId(), r.type(), r.thresholdDays(), r.enabled() == null || r.enabled());
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void update(@PathVariable UUID id, @Valid @RequestBody RuleRequest r) {
        int rows = jdbc.update("update alert_rules set threshold_days = coalesce(?, threshold_days), "
                + "enabled = coalesce(?, enabled) where id = ?", r.thresholdDays(), r.enabled(), id);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Rule not found");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void delete(@PathVariable UUID id) {
        if (jdbc.update("delete from alert_rules where id = ?", id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Rule not found");
        }
    }
}
