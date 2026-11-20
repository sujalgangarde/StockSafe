package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class PlatformTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<Map> post(String url, String token, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
    }

    @Test
    void writes_create_outbox_events_in_the_same_transaction() {
        Map a = signup("Outbox Store");
        String t = token(a);
        String shop = createShopRaw(t, "Main").getBody().get("id").toString();
        String p = post("/api/v1/shops/" + shop + "/products", t, Map.of("name", "Aspirin", "barcode", "OB1", "reorderLevel", 5))
                .getBody().get("id").toString();
        post("/api/v1/shops/" + shop + "/stock/receive", t, Map.of("productId", p, "batchNo", "B1",
                "expiryDate", LocalDate.now().plusDays(10).toString(), "cost", 1, "mrp", 2, "qty", 4));

        // outbox_events has no RLS (internal table), so we can count by tenant id directly
        List<String> types = jdbc.queryForList("select type from outbox_events where tenant_id = ?::uuid order by seq",
                String.class, tenantId(a).toString());
        assertThat(types).containsExactly("tenant.created", "product.upserted", "batch.changed");
    }

    @Test
    void api_key_reads_only_its_own_tenant() {
        Map a = signup("Key A");
        Map b = signup("Key B");
        String ta = token(a);
        String shopA = createShopRaw(ta, "Main").getBody().get("id").toString();
        post("/api/v1/shops/" + shopA + "/products", ta, Map.of("name", "Zinc", "barcode", "PK1"));
        String key = (String) post("/api/v1/api-keys", ta, Map.of("name", "pos")).getBody().get("apiKey");
        String keyB = (String) post("/api/v1/api-keys", token(b), Map.of("name", "pos")).getBody().get("apiKey");

        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", key);
        String url = "/api/v1/public/shops/" + shopA + "/stock?barcode=PK1";
        assertThat(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), Map.class).getStatusCode().value()).isEqualTo(200);

        HttpHeaders hb = new HttpHeaders();
        hb.set("X-API-Key", keyB);
        assertThat(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(hb), Map.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class)
                .getStatusCode().value()).isEqualTo(401);

        // API keys cannot use the normal API
        assertThat(rest.exchange("/api/v1/shops", HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void csv_and_pdf_exports_work() {
        Map a = signup("Export Store");
        String t = token(a);
        String shop = createShopRaw(t, "Main").getBody().get("id").toString();
        String p = post("/api/v1/shops/" + shop + "/products", t, Map.of("name", "Balm", "barcode", "EX1"))
                .getBody().get("id").toString();
        post("/api/v1/shops/" + shop + "/stock/receive", t, Map.of("productId", p, "batchNo", "B1",
                "expiryDate", LocalDate.now().plusDays(5).toString(), "cost", 1, "mrp", 2, "qty", 3));

        String csv = rest.exchange("/api/v1/shops/" + shop + "/reports/near-expiry/export?format=csv", HttpMethod.GET,
                new HttpEntity<>(auth(t)), String.class).getBody();
        assertThat(csv).contains("Balm").contains("B1");
        byte[] pdf = rest.exchange("/api/v1/shops/" + shop + "/reports/near-expiry/export?format=pdf", HttpMethod.GET,
                new HttpEntity<>(auth(t)), byte[].class).getBody();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void owner_adds_staff_and_staff_can_log_in_but_not_manage() {
        Map a = signup("Staff Store");
        String t = token(a);
        Map added = post("/api/v1/staff", t, Map.of("email", email(), "fullName", "Ravi", "role", "STAFF")).getBody();
        assertThat(added.get("temporaryPassword")).isNotNull();

        Map login = rest.postForEntity("/api/v1/auth/login",
                Map.of("email", added.get("email"), "password", added.get("temporaryPassword")), Map.class).getBody();
        assertThat(login.get("role")).isEqualTo("STAFF");
        String staffToken = (String) login.get("accessToken");
        assertThat(post("/api/v1/staff", staffToken, Map.of("email", email(), "fullName", "X", "role", "STAFF"))
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void platform_admin_needs_the_key() {
        signup("Admin Visible");
        assertThat(rest.getForEntity("/api/v1/admin/tenants", String.class).getStatusCode().value()).isEqualTo(403);
        HttpHeaders h = new HttpHeaders();
        h.set("X-Admin-Key", "test-admin-key");
        ResponseEntity<List> r = rest.exchange("/api/v1/admin/tenants", HttpMethod.GET, new HttpEntity<>(h), List.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isNotEmpty();
    }
}
