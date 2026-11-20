package com.stocksafe.core.purchase;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.security.CurrentUser;
import com.stocksafe.core.shop.ShopAccess;
import com.stocksafe.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseOrderService {
    public record ItemRequest(@NotNull UUID productId, @Min(1) int qty, @NotNull @DecimalMin("0") BigDecimal unitCost) {}

    public record CreateRequest(@NotNull UUID supplierId, @Size(max = 500) String notes,
                                @NotEmpty List<@Valid ItemRequest> items) {}

    // Allowed moves. RECEIVED and CANCELLED are final.
    private static final Map<String, List<String>> NEXT = Map.of(
            "DRAFT", List.of("SENT", "CANCELLED"),
            "SENT", List.of("RECEIVED", "CANCELLED"));

    private final JdbcTemplate jdbc;
    private final ShopAccess shops;
    private final AuditService audit;

    public PurchaseOrderService(JdbcTemplate jdbc, ShopAccess shops, AuditService audit) {
        this.jdbc = jdbc;
        this.shops = shops;
        this.audit = audit;
    }

    @Transactional
    public UUID create(UUID shopId, CreateRequest r) {
        shops.require(shopId);
        Integer sup = jdbc.queryForObject("select count(*) from suppliers where id = ? and deleted_at is null",
                Integer.class, r.supplierId());
        if (sup == null || sup == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Supplier not found");
        for (ItemRequest it : r.items()) {
            Integer p = jdbc.queryForObject(
                    "select count(*) from products where id = ? and shop_id = ? and deleted_at is null",
                    Integer.class, it.productId(), shopId);
            if (p == null || p == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Product not found: " + it.productId());
        }
        UUID tenant = TenantContext.require();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into purchase_orders(id, tenant_id, shop_id, supplier_id, notes, created_by) "
                + "values (?,?,?,?,?,?::uuid)", id, tenant, shopId, r.supplierId(), r.notes(), CurrentUser.id());
        for (ItemRequest it : r.items()) {
            jdbc.update("insert into po_items(id, tenant_id, po_id, product_id, qty, unit_cost) values (?,?,?,?,?,?)",
                    UUID.randomUUID(), tenant, id, it.productId(), it.qty(), it.unitCost());
        }
        audit.log("PO_CREATED", "purchase_order", id, Map.of("items", r.items().size()));
        return id;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID shopId) {
        shops.require(shopId);
        return jdbc.queryForList("select id, supplier_id, status, notes, created_at from purchase_orders "
                + "where shop_id = ? order by created_at desc limit 100", shopId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID shopId, UUID id) {
        shops.require(shopId);
        List<Map<String, Object>> po = jdbc.queryForList(
                "select id, supplier_id, status, notes, created_at from purchase_orders where id = ? and shop_id = ?",
                id, shopId);
        if (po.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Purchase order not found");
        Map<String, Object> out = new LinkedHashMap<>(po.get(0));
        out.put("items", jdbc.queryForList(
                "select product_id, qty, unit_cost from po_items where po_id = ?", id));
        return out;
    }

    @Transactional
    public void setStatus(UUID shopId, UUID id, String to) {
        shops.require(shopId);
        List<String> cur = jdbc.queryForList(
                "select status from purchase_orders where id = ? and shop_id = ?", String.class, id, shopId);
        if (cur.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Purchase order not found");
        if (!NEXT.getOrDefault(cur.get(0), List.of()).contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot move from " + cur.get(0) + " to " + to);
        }
        // "and status = ?" makes a double click or a race fail instead of overwriting.
        int rows = jdbc.update("update purchase_orders set status = ?, version = version + 1 "
                + "where id = ? and status = ?", to, id, cur.get(0));
        if (rows == 0) throw new ApiException(HttpStatus.CONFLICT, "Order changed. Please retry.");
        audit.log("PO_STATUS", "purchase_order", id, Map.of("from", cur.get(0), "to", to));
    }
}
