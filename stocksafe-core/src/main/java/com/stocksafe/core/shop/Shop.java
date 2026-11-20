package com.stocksafe.core.shop;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shops")
public class Shop {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private String name;

    @Enumerated(EnumType.STRING)
    private ShopTemplate template;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings;

    @Version
    private Long version;                 // optimistic locking

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;            // soft delete

    protected Shop() {}

    public Shop(UUID id, UUID tenantId, String name, ShopTemplate template, Map<String, Object> settings) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.template = template;
        this.settings = settings;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public ShopTemplate getTemplate() { return template; }
    public Map<String, Object> getSettings() { return settings; }
    public Instant getCreatedAt() { return createdAt; }
}
