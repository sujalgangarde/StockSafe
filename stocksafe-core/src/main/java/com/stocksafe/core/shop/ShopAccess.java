package com.stocksafe.core.shop;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.common.Json;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks a shop is visible to this tenant (RLS hides other tenants' shops). */
@Component
public class ShopAccess {
    private final JdbcTemplate jdbc;

    public ShopAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> settings(UUID shopId) {
        List<String> rows = jdbc.queryForList(
                "select settings::text from shops where id = ? and deleted_at is null", String.class, shopId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Shop not found");
        return Json.readMap(rows.get(0));
    }

    public void require(UUID shopId) {
        settings(shopId);
    }

    public boolean expiryRequired(UUID shopId) {
        return Boolean.TRUE.equals(settings(shopId).get("expiryRequired"));
    }
}
