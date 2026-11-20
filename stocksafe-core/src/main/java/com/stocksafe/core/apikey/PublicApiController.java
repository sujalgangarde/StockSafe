package com.stocksafe.core.apikey;

import com.stocksafe.core.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Small read-only public API. Version is in the path: /api/v1/public. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicApiController {
    private final JdbcTemplate jdbc;

    public PublicApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stock for one product by barcode: {name, barcode, qty, nearestExpiry}. */
    @GetMapping("/shops/{shopId}/stock")
    public Map<String, Object> stock(@PathVariable UUID shopId, @RequestParam String barcode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.name, p.barcode, p.unit, coalesce(sum(sb.qty), 0) as qty,
                       min(b.expiry_date) filter (where sb.qty > 0) as nearest_expiry
                from products p
                left join batches b on b.product_id = p.id and b.deleted_at is null
                left join stock_balances sb on sb.batch_id = b.id
                where p.shop_id = ? and p.barcode = ? and p.deleted_at is null
                group by p.id""", shopId, barcode);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Product not found");
        return rows.get(0);
    }
}
