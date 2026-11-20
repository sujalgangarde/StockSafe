package com.stocksafe.core.supplier;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SupplierService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID create(String name, String phone, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into suppliers(id, tenant_id, name, phone, email) values (?,?,?,?,?)",
                id, TenantContext.require(), name.trim(), phone, email);
        audit.log("SUPPLIER_CREATED", "supplier", id, Map.of("name", name));
        return id;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, name, phone, email, created_at from suppliers "
                + "where deleted_at is null order by name");
    }

    @Transactional
    public void delete(UUID id) {
        int rows = jdbc.update("update suppliers set deleted_at = now() where id = ? and deleted_at is null", id);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Supplier not found");
        audit.log("SUPPLIER_DELETED", "supplier", id, Map.of());
    }
}
