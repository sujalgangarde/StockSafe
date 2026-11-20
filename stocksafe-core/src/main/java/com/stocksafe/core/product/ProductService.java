package com.stocksafe.core.product;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.common.Json;
import com.stocksafe.core.outbox.OutboxService;
import com.stocksafe.core.shop.ShopAccess;
import com.stocksafe.core.tenant.TenantContext;
import com.stocksafe.core.usage.UsageService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    public record Product(UUID id, UUID shopId, String name, String barcode, String unit, BigDecimal gstRate,
                          int reorderLevel, Map<String, Object> customAttributes) {}

    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 64) String barcode,
            @Size(max = 20) String unit,
            @DecimalMin("0") @DecimalMax("28") BigDecimal gstRate,
            @Min(0) Integer reorderLevel,
            Map<String, Object> customAttributes) {}

    private static final String COLS =
            "id, shop_id, name, barcode, unit, gst_rate, reorder_level, custom_attributes::text as attrs";

    private static final RowMapper<Product> MAPPER = (rs, i) -> new Product(
            rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class), rs.getString("name"),
            rs.getString("barcode"), rs.getString("unit"), rs.getBigDecimal("gst_rate"),
            rs.getInt("reorder_level"), Json.readMap(rs.getString("attrs")));

    private final JdbcTemplate jdbc;
    private final UsageService usage;
    private final ShopAccess shops;
    private final AuditService audit;
    private final OutboxService outbox;

    public ProductService(JdbcTemplate jdbc, UsageService usage, ShopAccess shops, AuditService audit,
                          OutboxService outbox) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.usage = usage;
        this.shops = shops;
        this.audit = audit;
    }

    @Transactional
    public Product create(UUID shopId, ProductRequest r) {
        shops.require(shopId);
        UUID id = createInternal(shopId, r.name(), r.barcode(), r.unit(), r.gstRate(),
                r.reorderLevel() == null ? 0 : r.reorderLevel(), r.customAttributes());
        audit.log("PRODUCT_CREATED", "product", id, Map.of("name", r.name()));
        return get(shopId, id);
    }

    /**
     * Shared by the API and CSV import. Not @Transactional on purpose: the caller owns the transaction.
     * Throws ApiException BEFORE any write that could break the transaction.
     */
    public UUID createInternal(UUID shopId, String name, String barcode, String unit, BigDecimal gst,
                               int reorder, Map<String, Object> attrs) {
        usage.consumeOrThrow("products");
        UUID id = UUID.randomUUID();
        String bc = (barcode == null || barcode.isBlank()) ? null : barcode.trim();
        int rows = jdbc.update("""
                insert into products(id, tenant_id, shop_id, name, barcode, unit, gst_rate, reorder_level, custom_attributes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (tenant_id, shop_id, barcode) where deleted_at is null and barcode is not null
                do nothing""",
                id, TenantContext.require(), shopId, name.trim(), bc,
                (unit == null || unit.isBlank()) ? "pcs" : unit.trim(),
                gst == null ? BigDecimal.ZERO : gst, reorder,
                Json.write(attrs == null ? Map.of() : attrs));
        if (rows == 0) {
            usage.release("products");   // give the slot back
            throw new ApiException(HttpStatus.CONFLICT, "Barcode already exists in this shop");
        }
        outbox.publish("product.upserted", Map.of("productId", id.toString(), "shopId", shopId.toString(),
                "name", name.trim(), "reorderLevel", reorder, "deleted", false));
        return id;
    }

    @Transactional(readOnly = true)
    public Product get(UUID shopId, UUID id) {
        List<Product> rows = jdbc.query("select " + COLS + " from products where id = ? and shop_id = ? and deleted_at is null",
                MAPPER, id, shopId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Product not found");
        return rows.get(0);
    }

    /** Search: exact barcode, or full-text on name, or "contains" on name (for partial words). */
    @Transactional(readOnly = true)
    public List<Product> list(UUID shopId, String q, String barcode, int page, int size) {
        shops.require(shopId);
        StringBuilder sql = new StringBuilder("select " + COLS + " from products where shop_id = ? and deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(shopId);
        if (barcode != null && !barcode.isBlank()) {
            sql.append(" and barcode = ?");
            args.add(barcode.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" and (search_vec @@ websearch_to_tsquery('simple', ?) or name ilike ?)");
            args.add(q.trim());
            args.add("%" + q.trim().replace("%", "").replace("_", "") + "%");
        }
        int s = Math.min(Math.max(size, 1), 100);
        sql.append(" order by name limit ? offset ?");
        args.add(s);
        args.add(Math.max(page, 0) * s);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Transactional
    public void delete(UUID shopId, UUID id) {
        int rows = jdbc.update("update products set deleted_at = now(), updated_at = now() "
                + "where id = ? and shop_id = ? and deleted_at is null", id, shopId);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Product not found");
        usage.release("products");
        outbox.publish("product.upserted", Map.of("productId", id.toString(), "shopId", shopId.toString(),
                "name", "", "reorderLevel", 0, "deleted", true));
        audit.log("PRODUCT_DELETED", "product", id, Map.of());
    }
}
