package com.stocksafe.alerts;

import com.stocksafe.alerts.common.Json;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Real Postgres 16 with the same role script as docker-compose. No RabbitMQ: tests call the handler directly. */
@SpringBootTest
public abstract class AbstractAlertsTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stocksafe").withUsername("stocksafe").withPassword("stocksafe")
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../docker/postgres/init/01-roles.sql").toAbsolutePath().normalize().toString()),
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
        r.add("stocksafe.relay.enabled", () -> "false");
        r.add("stocksafe.jobs.enabled", () -> "false");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        r.add("management.health.rabbit.enabled", () -> "false");
        r.add("management.tracing.enabled", () -> "false");
    }

    private static long seq = 1000;

    protected static String event(UUID eventId, UUID tenant, String type, long seq, Map<String, Object> data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("tenantId", tenant.toString());
        env.put("type", type);
        env.put("seq", seq);
        env.put("occurredAt", Instant.now().toString());
        env.put("correlationId", "test");
        env.put("data", data);
        return Json.write(env);
    }

    protected static synchronized long nextSeq() { return ++seq; }

    protected static Map<String, Object> batchData(UUID shop, UUID product, UUID batch, int qty, LocalDate expiry,
                                                   int reorder, String movementType, int delta) {
        Map<String, Object> d = new HashMap<>();
        d.put("shopId", shop.toString());
        d.put("productId", product.toString());
        d.put("productName", "Paracetamol");
        d.put("reorderLevel", reorder);
        d.put("batchId", batch.toString());
        d.put("batchNo", "B1");
        d.put("expiryDate", expiry == null ? null : expiry.toString());
        d.put("qty", qty);
        d.put("delta", delta);
        d.put("movementType", movementType);
        return d;
    }
}
