package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocksafe.core.security.JwtService;
import com.stocksafe.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    private int count(UUID tenant, String sql, Object... args) {
        return TenantContext.callAs(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void tenantB_cannot_see_tenantA_shops_through_the_api() {
        Map a = signup("A Medicals");
        Map b = signup("B Medicals");
        String shopA = createShopRaw(token(a), "A Main").getBody().get("id").toString();

        List listB = rest.exchange("/api/v1/shops", HttpMethod.GET,
                new HttpEntity<>(auth(token(b))), List.class).getBody();
        assertThat(listB).isEmpty();

        assertThat(status(HttpMethod.GET, "/api/v1/shops/" + shopA, token(b))).isEqualTo(404);
        assertThat(status(HttpMethod.DELETE, "/api/v1/shops/" + shopA, token(b))).isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/api/v1/shops/" + shopA, token(a))).isEqualTo(200);
    }

    @Test
    void rls_blocks_raw_sql_even_when_app_code_forgets_a_filter() {
        Map a = signup("A Raw");
        Map b = signup("B Raw");
        UUID tA = tenantId(a), tB = tenantId(b);
        UUID shopA = UUID.fromString(createShopRaw(token(a), "A Main").getBody().get("id").toString());

        assertThat(count(tA, "select count(*) from shops where id = ?", shopA)).isEqualTo(1);
        assertThat(count(tB, "select count(*) from shops where id = ?", shopA)).isZero();
        assertThat(count(null, "select count(*) from shops")).isZero();
        assertThat(count(tB, "select count(*) from tenants")).isEqualTo(1);

        assertThatThrownBy(() -> TenantContext.callAs(tB, () -> jdbc.update(
                "insert into shops(id, tenant_id, name, template) values (?,?,?,?)",
                UUID.randomUUID(), tA, "evil", "CUSTOM"))).isInstanceOf(DataAccessException.class);

        assertThat(TenantContext.callAs(tB, () -> jdbc.update("update shops set name = 'hacked' where id = ?", shopA))).isZero();
        assertThat(TenantContext.callAs(tB, () -> jdbc.update("delete from shops where id = ?", shopA))).isZero();

        for (int i = 0; i < 20; i++) count(i % 2 == 0 ? tA : null, "select count(*) from shops");
        assertThat(count(null, "select count(*) from shops")).isZero();
    }

    @Test
    void free_plan_allows_one_shop_only() {
        Map a = signup("Free Shop");
        assertThat(createShopRaw(token(a), "First").getStatusCode().value()).isEqualTo(201);
        assertThat(createShopRaw(token(a), "Second").getStatusCode().value()).isEqualTo(402);
    }

    @Test
    void staff_can_read_but_not_create_shops() {
        Map a = signup("Role Test");
        String staffToken = jwt.createAccessToken(UUID.randomUUID(), tenantId(a), "STAFF");
        assertThat(createShopRaw(staffToken, "Nope").getStatusCode().value()).isEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/v1/shops", staffToken)).isEqualTo(200);
    }

    @Test
    void no_token_means_401() {
        assertThat(rest.getForEntity("/api/v1/shops", String.class).getStatusCode().value()).isEqualTo(401);
    }
}
