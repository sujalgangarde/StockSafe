package com.stocksafe.core.staff;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.security.CurrentUser;
import com.stocksafe.core.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Owner adds staff to the shop. Roles: MANAGER (full stock work) and STAFF (sell only). */
@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasRole('OWNER')")
public class StaffController {
    public record AddStaffRequest(@NotBlank @Email String email, @NotBlank @Size(max = 120) String fullName,
                                  @NotBlank @Pattern(regexp = "MANAGER|STAFF") String role) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public StaffController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                select u.id, u.email, u.full_name, m.role
                from memberships m join users u on u.id = m.user_id
                where m.deleted_at is null order by m.created_at""");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> add(@Valid @RequestBody AddStaffRequest r) {
        String email = r.email().trim().toLowerCase();
        Map<String, Object> out = new LinkedHashMap<>();
        List<UUID> existing = jdbc.queryForList(
                "select id from users where lower(email) = ? and deleted_at is null", UUID.class, email);
        UUID userId;
        if (existing.isEmpty()) {
            userId = UUID.randomUUID();
            StringBuilder pw = new StringBuilder();
            for (int i = 0; i < 12; i++) pw.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            jdbc.update("insert into users(id, email, password_hash, full_name) values (?,?,?,?)",
                    userId, email, encoder.encode(pw), r.fullName());
            out.put("temporaryPassword", pw.toString());       // shown once
        } else {
            userId = existing.get(0);
        }
        jdbc.update("""
                insert into memberships(id, tenant_id, user_id, role) values (?,?,?,?)
                on conflict (tenant_id, user_id) do update set role = excluded.role, deleted_at = null""",
                UUID.randomUUID(), TenantContext.require(), userId, r.role());
        audit.log("STAFF_ADDED", "user", userId, Map.of("role", r.role()));
        out.put("userId", userId);
        out.put("email", email);
        out.put("role", r.role());
        return out;
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@PathVariable UUID userId) {
        if (userId.equals(CurrentUser.id())) throw new ApiException(HttpStatus.CONFLICT, "You cannot remove yourself");
        int rows = jdbc.update("update memberships set deleted_at = now() "
                + "where user_id = ? and role <> 'OWNER' and deleted_at is null", userId);
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Staff member not found");
        audit.log("STAFF_REMOVED", "user", userId, Map.of());
    }
}
