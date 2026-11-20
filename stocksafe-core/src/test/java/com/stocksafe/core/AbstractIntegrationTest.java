package com.stocksafe.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** One real Postgres 16 for all tests, created with the SAME role script as docker-compose. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stocksafe")
            .withUsername("stocksafe")
            .withPassword("stocksafe")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../docker/postgres/init/01-roles.sql").toAbsolutePath().normalize().toString()),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "stocksafe_app");
        r.add("spring.datasource.password", () -> "stocksafe_app");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", () -> "stocksafe");
        r.add("spring.flyway.password", () -> "stocksafe");
        // No RabbitMQ / Redis / tracing backend in tests
        r.add("stocksafe.relay.enabled", () -> "false");
        r.add("stocksafe.ratelimit.enabled", () -> "false");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        r.add("management.health.rabbit.enabled", () -> "false");
        r.add("management.health.redis.enabled", () -> "false");
        r.add("management.tracing.enabled", () -> "false");
        r.add("stocksafe.billing.mock", () -> "true");
        r.add("stocksafe.admin.key", () -> "test-admin-key");
    }

    @Autowired protected TestRestTemplate rest;

    protected static String email() {
        return "u-" + UUID.randomUUID() + "@test.com";
    }

    protected ResponseEntity<Map> signupRaw(String email, String business) {
        return rest.postForEntity("/api/v1/auth/signup",
                Map.of("email", email, "password", "Passw0rd!", "fullName", "Test User", "businessName", business),
                Map.class);
    }

    protected Map signup(String business) {
        return signupRaw(email(), business).getBody();
    }

    protected static String token(Map signup) { return (String) signup.get("accessToken"); }

    protected static UUID tenantId(Map signup) { return UUID.fromString((String) signup.get("tenantId")); }

    protected static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    protected ResponseEntity<Map> createShopRaw(String token, String name) {
        return rest.exchange("/api/v1/shops", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "template", "MEDICAL"), auth(token)), Map.class);
    }

    protected int status(HttpMethod method, String url, String token) {
        return rest.exchange(url, method, new HttpEntity<>(auth(token)), String.class).getStatusCode().value();
    }
}
