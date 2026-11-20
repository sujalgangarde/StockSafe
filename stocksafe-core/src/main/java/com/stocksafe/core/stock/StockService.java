package com.stocksafe.core.stock;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.outbox.OutboxService;
import com.stocksafe.core.security.CurrentUser;
import com.stocksafe.core.sse.SseHub;
import com.stocksafe.core.shop.ShopAccess;
import com.stocksafe.core.tenant.TenantContext;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock = movements (truth) + cached balance per batch.
 * Every change writes both in ONE transaction. Selling uses FEFO and optimistic version checks.
 */
@Service
public class StockService {
    public record ReceiveRequest(
            @NotNull UUID productId,
            @NotBlank @Size(max = 64) String batchNo,
            LocalDate expiryDate,
            @NotNull @DecimalMin("0") BigDecimal cost,
            @NotNull @DecimalMin("0") BigDecimal mrp,
            @Min(1) int qty,
            UUID supplierId) {}

    public record SellRequest(@NotNull UUID productId, @Min(1) int qty, @Size(max = 100) String ref) {}

    public record AdjustRequest(@NotNull UUID batchId, int delta, @NotBlank @Size(max = 200) String reason) {}

    public record Allocation(UUID batchId, String batchNo, LocalDate expiryDate, int qty) {}

    private record FefoRow(UUID batchId, String batchNo, LocalDate expiry, int qty, long version) {}

    private final JdbcTemplate jdbc;
    private final ShopAccess shops;
    private final AuditService audit;
    private final OutboxService outbox;
    private final SseHub sse;

    public StockService(JdbcTemplate jdbc, ShopAccess shops, AuditService audit, OutboxService outbox, SseHub sse) {
        this.outbox = outbox;
        this.sse = sse;
        this.jdbc = jdbc;
        this.shops = shops;
        this.audit = audit;
    }

    // ---------- receive ----------

    @Transactional
    public UUID receive(UUID shopId, ReceiveRequest r) {
        boolean expiryRequired = shops.expiryRequired(shopId);
        UUID batchId = doReceive(shopId, expiryRequired, r, CurrentUser.id());
        audit.log("STOCK_RECEIVED", "batch", batchId, Map.of("productId", r.productId().toString(), "qty", r.qty()));
        sse.publish(shopId, "stock.changed", Map.of("productId", r.productId().toString(), "type", "RECEIVE"));
        return batchId;
    }

    /** Shared with CSV import. Not @Transactional: the caller owns the transaction. Validates before writing. */
    public UUID doReceive(UUID shopId, boolean expiryRequired, ReceiveRequest r, UUID userId) {
        requireProduct(shopId, r.productId());
        if (expiryRequired && r.expiryDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Expiry date is required for this shop");
        }
        UUID tenant = TenantContext.require();
        UUID batchId = UUID.randomUUID();
        int ins = jdbc.update("""
                insert into batches(id, tenant_id, shop_id, product_id, supplier_id, batch_no, expiry_date, cost, mrp, qty_received)
                values (?, ?, ?, ?, ?::uuid, ?, ?::date, ?, ?, ?)
                on conflict (tenant_id, product_id, batch_no) do nothing""",
                batchId, tenant, shopId, r.productId(), r.supplierId(), r.batchNo().trim(), r.expiryDate(),
                r.cost(), r.mrp(), r.qty());
        if (ins == 0) throw new ApiException(HttpStatus.CONFLICT, "Batch number already exists for this product");
        jdbc.update("insert into stock_balances(batch_id, tenant_id, shop_id, product_id, qty) values (?,?,?,?,?)",
                batchId, tenant, shopId, r.productId(), r.qty());
        move(tenant, shopId, r.productId(), batchId, "RECEIVE", r.qty(), null, null, userId);
        publishBatch(shopId, r.productId(), batchId, "RECEIVE", r.qty());
        return batchId;
    }

    // ---------- sell (FEFO) ----------

    @Transactional
    public List<Allocation> sell(UUID shopId, SellRequest r) {
        shops.require(shopId);
        requireProduct(shopId, r.productId());
        UUID tenant = TenantContext.require();

        // Lock the balance rows we may touch, earliest expiry first. Expired batches are skipped.
        List<FefoRow> rows = jdbc.query("""
                select b.id, b.batch_no, b.expiry_date, sb.qty, sb.version
                from batches b join stock_balances sb on sb.batch_id = b.id
                where b.product_id = ? and b.shop_id = ? and b.deleted_at is null and sb.qty > 0
                  and (b.expiry_date is null or b.expiry_date >= current_date)
                order by b.expiry_date nulls last, b.created_at, b.id
                for update of sb""",
                (rs, i) -> new FefoRow(rs.getObject("id", UUID.class), rs.getString("batch_no"),
                        rs.getObject("expiry_date", LocalDate.class), rs.getInt("qty"), rs.getLong("version")),
                r.productId(), shopId);

        int available = rows.stream().mapToInt(FefoRow::qty).sum();
        if (available < r.qty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not enough sellable stock. Available: " + available);
        }

        List<Allocation> out = new ArrayList<>();
        int need = r.qty();
        for (FefoRow row : rows) {
            if (need == 0) break;
            int take = Math.min(row.qty(), need);
            int upd = jdbc.update("update stock_balances set qty = qty - ?, version = version + 1, updated_at = now() "
                    + "where batch_id = ? and version = ? and qty >= ?", take, row.batchId(), row.version(), take);
            if (upd == 0) throw new ApiException(HttpStatus.CONFLICT, "Stock changed. Please retry.");
            move(tenant, shopId, r.productId(), row.batchId(), "SALE", -take, null, r.ref(), CurrentUser.id());
            publishBatch(shopId, r.productId(), row.batchId(), "SALE", -take);
            out.add(new Allocation(row.batchId(), row.batchNo(), row.expiry(), take));
            need -= take;
        }
        audit.log("STOCK_SOLD", "product", r.productId(), Map.of("qty", r.qty()));
        sse.publish(shopId, "stock.changed", Map.of("productId", r.productId().toString(), "type", "SALE"));
        return out;
    }

    // ---------- adjust ----------

    @Transactional
    public void adjust(UUID shopId, AdjustRequest r) {
        if (r.delta() == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Delta cannot be zero");
        shops.require(shopId);
        List<UUID> product = jdbc.queryForList(
                "select product_id from batches where id = ? and shop_id = ? and deleted_at is null",
                UUID.class, r.batchId(), shopId);
        if (product.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Batch not found");
        int upd = jdbc.update("update stock_balances set qty = qty + ?, version = version + 1, updated_at = now() "
                + "where batch_id = ? and qty + ? >= 0", r.delta(), r.batchId(), r.delta());
        if (upd == 0) throw new ApiException(HttpStatus.CONFLICT, "Stock cannot go below zero");
        move(TenantContext.require(), shopId, product.get(0), r.batchId(), "ADJUST", r.delta(), r.reason(), null,
                CurrentUser.id());
        publishBatch(shopId, product.get(0), r.batchId(), "ADJUST", r.delta());
        sse.publish(shopId, "stock.changed", Map.of("productId", product.get(0).toString(), "type", "ADJUST"));
        audit.log("STOCK_ADJUSTED", "batch", r.batchId(), Map.of("delta", r.delta(), "reason", r.reason()));
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> batches(UUID shopId, UUID productId) {
        shops.require(shopId);
        return jdbc.queryForList("""
                select b.id, b.batch_no, b.expiry_date, b.cost, b.mrp, b.qty_received, sb.qty
                from batches b join stock_balances sb on sb.batch_id = b.id
                where b.shop_id = ? and b.product_id = ? and b.deleted_at is null
                order by b.expiry_date nulls last, b.created_at""", shopId, productId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> movements(UUID shopId, UUID productId, int limit) {
        shops.require(shopId);
        return jdbc.queryForList("""
                select id, batch_id, type, qty_delta, reason, ref, occurred_at
                from stock_movements where shop_id = ? and product_id = ?
                order by occurred_at desc limit ?""", shopId, productId, Math.min(Math.max(limit, 1), 500));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> nearExpiry(UUID shopId, int days) {
        shops.require(shopId);
        return jdbc.queryForList("""
                select p.id as product_id, p.name, b.id as batch_id, b.batch_no, b.expiry_date, sb.qty,
                       (b.expiry_date < current_date) as expired
                from batches b
                join stock_balances sb on sb.batch_id = b.id
                join products p on p.id = b.product_id and p.deleted_at is null
                where b.shop_id = ? and b.deleted_at is null and sb.qty > 0
                  and b.expiry_date is not null and b.expiry_date <= current_date + ?
                order by b.expiry_date""", shopId, Math.min(Math.max(days, 0), 365));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> lowStock(UUID shopId) {
        shops.require(shopId);
        return jdbc.queryForList("""
                select p.id as product_id, p.name, p.barcode, p.reorder_level, coalesce(sum(sb.qty), 0) as qty
                from products p left join stock_balances sb on sb.product_id = p.id
                where p.shop_id = ? and p.deleted_at is null and p.reorder_level > 0
                group by p.id
                having coalesce(sum(sb.qty), 0) <= p.reorder_level
                order by p.name""", shopId);
    }

    /** Dead stock: we hold it, but sold none in the last N days. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadStock(UUID shopId, int days) {
        shops.require(shopId);
        return jdbc.queryForList("""
                select p.id as product_id, p.name, sum(sb.qty) as qty
                from products p join stock_balances sb on sb.product_id = p.id
                where p.shop_id = ? and p.deleted_at is null
                group by p.id
                having sum(sb.qty) > 0
                   and not exists (select 1 from stock_movements m
                                   where m.product_id = p.id and m.type = 'SALE'
                                     and m.occurred_at > now() - make_interval(days => ?))
                order by p.name""", shopId, Math.min(Math.max(days, 1), 3650));
    }

    // ---------- helpers ----------

    /** The alerts service keeps its own copy of batch data, built only from these events. */
    private void publishBatch(UUID shopId, UUID productId, UUID batchId, String type, int delta) {
        Map<String, Object> b = jdbc.queryForMap("""
                select p.name, p.reorder_level, b.batch_no, b.expiry_date::text as expiry, sb.qty
                from batches b join products p on p.id = b.product_id
                join stock_balances sb on sb.batch_id = b.id where b.id = ?""", batchId);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("shopId", shopId.toString());
        data.put("productId", productId.toString());
        data.put("productName", b.get("name"));
        data.put("reorderLevel", b.get("reorder_level"));
        data.put("batchId", batchId.toString());
        data.put("batchNo", b.get("batch_no"));
        data.put("expiryDate", b.get("expiry"));
        data.put("qty", b.get("qty"));
        data.put("delta", delta);
        data.put("movementType", type);
        outbox.publish("batch.changed", data);
    }

    private void requireProduct(UUID shopId, UUID productId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from products where id = ? and shop_id = ? and deleted_at is null",
                Integer.class, productId, shopId);
        if (n == null || n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Product not found");
    }

    private void move(UUID tenant, UUID shopId, UUID productId, UUID batchId, String type, int delta,
                      String reason, String ref, UUID userId) {
        jdbc.update("""
                insert into stock_movements(id, tenant_id, shop_id, product_id, batch_id, type, qty_delta, reason, ref, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)""",
                UUID.randomUUID(), tenant, shopId, productId, batchId, type, delta, reason, ref, userId);
    }
}
