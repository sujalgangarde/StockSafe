package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocksafe.core.security.JwtService;
import com.stocksafe.core.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class StockFlowTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    private String shopOf(Map signup) {
        return createShopRaw(token(signup), "Main").getBody().get("id").toString();
    }

    private ResponseEntity<Map> post(String url, String token, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
    }

    private String product(String token, String shop, String name, String barcode) {
        return post("/api/v1/shops/" + shop + "/products", token, Map.of(
                "name", name, "barcode", barcode, "unit", "strip", "gstRate", 12, "reorderLevel", 20,
                "customAttributes", Map.of("manufacturer", "Cipla"))).getBody().get("id").toString();
    }

    private void receive(String token, String shop, String product, String batchNo, LocalDate expiry, int qty) {
        ResponseEntity<Map> r = post("/api/v1/shops/" + shop + "/stock/receive", token, Map.of(
                "productId", product, "batchNo", batchNo, "expiryDate", expiry.toString(),
                "cost", 10, "mrp", 15, "qty", qty));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
    }

    // Object.class: success returns a list, errors return a problem object.
    private ResponseEntity<Object> sell(String token, String shop, String product, int qty) {
        return rest.exchange("/api/v1/shops/" + shop + "/stock/sell", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", product, "qty", qty), auth(token)), Object.class);
    }

    private long sumAsTenant(UUID tenant, String sql, Object... args) {
        return TenantContext.callAs(tenant, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    @Test
    void fefo_sells_earliest_expiry_first_and_balance_matches_movements() {
        Map a = signup("FEFO Medicals");
        String t = token(a);
        String shop = shopOf(a);
        String p = product(t, shop, "Paracetamol 500", "890100000001");

        receive(t, shop, p, "LATE", LocalDate.now().plusDays(200), 10);
        receive(t, shop, p, "EARLY", LocalDate.now().plusDays(100), 10);
        receive(t, shop, p, "EXPIRING", LocalDate.now().plusDays(1), 5);
        // Already expired: held in stock, but must never be sold
        receive(t, shop, p, "EXPIRED", LocalDate.now().minusDays(1), 50);

        // 5 (EXPIRING) + 10 (EARLY) + 5 (LATE) = 20 in FEFO order
        ResponseEntity<Object> r = sell(t, shop, p, 18);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        List<Map> alloc = (List<Map>) r.getBody();
        assertThat(alloc).extracting(m -> m.get("batchNo")).containsExactly("EXPIRING", "EARLY", "LATE");
        assertThat(alloc).extracting(m -> m.get("qty")).containsExactly(5, 10, 3);

        // balance table equals the sum of movements (7 sellable left + 50 expired)
        UUID tenant = tenantId(a);
        UUID pid = UUID.fromString(p);
        assertThat(sumAsTenant(tenant, "select coalesce(sum(qty),0) from stock_balances where product_id = ?", pid)).isEqualTo(57);
        assertThat(sumAsTenant(tenant, "select coalesce(sum(qty_delta),0) from stock_movements where product_id = ?", pid)).isEqualTo(57);

        // Only 7 sellable (the expired 50 are skipped): 409 and nothing changes
        assertThat(sell(t, shop, p, 8).getStatusCode().value()).isEqualTo(409);
        assertThat(sumAsTenant(tenant, "select coalesce(sum(qty),0) from stock_balances where product_id = ?", pid)).isEqualTo(57);
    }

    @Test
    void movements_land_in_a_monthly_partition_and_app_cannot_edit_history() {
        Map a = signup("Partition Store");
        String t = token(a);
        String shop = shopOf(a);
        String p = product(t, shop, "Cough Syrup", "890100000002");
        receive(t, shop, p, "B1", LocalDate.now().plusDays(90), 3);

        String table = TenantContext.callAs(tenantId(a), () -> jdbc.queryForObject(
                "select tableoid::regclass::text from stock_movements limit 1", String.class));
        assertThat(table).startsWith("stock_movements_");

        int updated = 0;
        try {
            TenantContext.callAs(tenantId(a), () -> jdbc.update("update stock_movements set qty_delta = 99"));
        } catch (Exception expected) {
            updated = -1;      // permission denied: history is append-only
        }
        assertThat(updated).isEqualTo(-1);
    }

    @Test
    void other_tenant_cannot_use_my_shop_or_products() {
        Map a = signup("Owner A");
        Map b = signup("Owner B");
        String shopA = shopOf(a);
        String p = product(token(a), shopA, "Vitamin C", "890100000003");

        assertThat(status(HttpMethod.GET, "/api/v1/shops/" + shopA + "/products", token(b))).isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/api/v1/shops/" + shopA + "/products/" + p, token(b))).isEqualTo(404);
        assertThat(sell(token(b), shopA, p, 1).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void search_by_barcode_and_name_and_duplicate_barcode_rejected() {
        Map a = signup("Search Store");
        String t = token(a);
        String shop = shopOf(a);
        product(t, shop, "Amoxicillin 250", "890100000004");

        List byBarcode = rest.exchange("/api/v1/shops/" + shop + "/products?barcode=890100000004", HttpMethod.GET,
                new HttpEntity<>(auth(t)), List.class).getBody();
        assertThat(byBarcode).hasSize(1);
        List byName = rest.exchange("/api/v1/shops/" + shop + "/products?q=amoxi", HttpMethod.GET,
                new HttpEntity<>(auth(t)), List.class).getBody();
        assertThat(byName).hasSize(1);

        ResponseEntity<Map> dup = post("/api/v1/shops/" + shop + "/products", t,
                Map.of("name", "Other", "barcode", "890100000004"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void staff_can_sell_but_not_receive() {
        Map a = signup("Staff Store");
        String shop = shopOf(a);
        String p = product(token(a), shop, "Bandage", "890100000005");
        receive(token(a), shop, p, "B1", LocalDate.now().plusDays(300), 5);

        String staff = jwt.createAccessToken(UUID.randomUUID(), tenantId(a), "STAFF");
        assertThat(sell(staff, shop, p, 1).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> r = post("/api/v1/shops/" + shop + "/stock/receive", staff, Map.of(
                "productId", p, "batchNo", "X", "expiryDate", LocalDate.now().plusDays(10).toString(),
                "cost", 1, "mrp", 2, "qty", 1));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }
}
