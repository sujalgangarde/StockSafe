package com.stocksafe.alerts.api;

import com.stocksafe.alerts.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AlertController {
    private final JdbcTemplate jdbc;

    public AlertController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(required = false) UUID shopId,
                                            @RequestParam(defaultValue = "OPEN") String status) {
        StringBuilder sql = new StringBuilder("select id, shop_id, type, product_id, batch_id, message, status, created_at "
                + "from alerts where status = ?");
        List<Object> args = new ArrayList<>(List.of(status));
        if (shopId != null) {
            sql.append(" and shop_id = ?");
            args.add(shopId);
        }
        sql.append(" order by created_at desc limit 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/alerts/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID id) {
        int rows = jdbc.update("update alerts set status = 'RESOLVED', resolved_at = now() where id = ? and status = 'OPEN'", id);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Open alert not found");
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications() {
        return jdbc.queryForList("select id, alert_id, channel, recipient, subject, status, attempts, last_error, created_at, sent_at "
                + "from notifications order by created_at desc limit 100");
    }

    @PostMapping("/notifications/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID id) {
        int rows = jdbc.update("update notifications set status = 'PENDING', attempts = 0, next_attempt_at = now() "
                + "where id = ? and status = 'FAILED'", id);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Failed notification not found");
    }
}
