package com.stocksafe.core.apikey;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Owner creates keys for the small public API (/api/v1/public/**). The key is shown once. */
@RestController
@RequestMapping("/api/v1/api-keys")
@PreAuthorize("hasRole('OWNER')")
public class ApiKeyController {
    public record CreateKeyRequest(@NotBlank @Size(max = 80) String name) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public ApiKeyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateKeyRequest r) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String key = "ssk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into api_keys(id, tenant_id, name, prefix, key_hash) values (?,?,?,?,?)",
                id, TenantContext.require(), r.name(), key.substring(0, 8), ApiKeyFilter.sha256(key));
        return Map.of("id", id, "name", r.name(), "apiKey", key);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, name, prefix, created_at, revoked_at from api_keys order by created_at desc");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        int rows = jdbc.update("update api_keys set revoked_at = now() where id = ? and revoked_at is null", id);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Key not found");
    }
}
