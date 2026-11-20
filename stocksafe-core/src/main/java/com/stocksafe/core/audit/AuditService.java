package com.stocksafe.core.audit;

import com.stocksafe.core.common.Json;
import com.stocksafe.core.security.CurrentUser;
import com.stocksafe.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Who did what. Rows are append-only (the app role has no UPDATE or DELETE). */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String action, String entity, UUID entityId, Map<String, Object> details) {
        jdbc.update("insert into audit_log(id, tenant_id, user_id, action, entity, entity_id, details) "
                        + "values (?, ?, ?::uuid, ?, ?, ?::uuid, ?::jsonb)",
                UUID.randomUUID(), TenantContext.require(), CurrentUser.id(), action, entity, entityId,
                Json.write(details == null ? Map.of() : details));
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("select id, user_id, action, entity, entity_id, details::text as details, created_at "
                + "from audit_log order by created_at desc limit ?", Math.min(Math.max(limit, 1), 200));
    }
}
