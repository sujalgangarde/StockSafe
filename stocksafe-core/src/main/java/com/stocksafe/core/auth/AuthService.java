package com.stocksafe.core.auth;

import com.stocksafe.core.auth.AuthDtos.*;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.outbox.OutboxService;
import com.stocksafe.core.security.JwtService;
import com.stocksafe.core.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final TransactionTemplate tx;
    private final long refreshDays;
    private final OutboxService outbox;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, TransactionTemplate tx,
                       OutboxService outbox, @Value("${stocksafe.jwt.refresh-ttl-days}") long refreshDays) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.tx = tx;
        this.refreshDays = refreshDays;
    }

    private record Member(UUID tenantId, String role) {}
    private record RefreshRow(UUID userId, UUID tenantId) {}

    /** Create user + tenant + OWNER membership + usage counters in one transaction. */
    public TokenResponse signup(SignupRequest r) {
        String email = r.email().trim().toLowerCase();
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        try {
            // Set the tenant FIRST, then open the transaction, so its connection carries the tenant.
            return TenantContext.callAs(tenantId, () -> tx.execute(status -> {
                jdbc.update("insert into users(id, email, password_hash, full_name) values (?,?,?,?)",
                        userId, email, encoder.encode(r.password()), r.fullName());
                jdbc.update("insert into tenants(id, name, plan_code) values (?,?, 'free')",
                        tenantId, r.businessName());
                jdbc.update("insert into memberships(id, tenant_id, user_id, role) values (?,?,?, 'OWNER')",
                        UUID.randomUUID(), tenantId, userId);
                jdbc.update("insert into usage_counters(tenant_id, metric, value) values (?, 'shops', 0), (?, 'products', 0)",
                        tenantId, tenantId);
                outbox.publish("tenant.created", java.util.Map.of("name", r.businessName(), "ownerEmail", email, "plan", "free"));
                return issueTokens(userId, tenantId, "OWNER");
            }));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
    }

    public TokenResponse login(LoginRequest r) {
        String email = r.email().trim().toLowerCase();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, password_hash from users where lower(email) = ? and deleted_at is null", email);
        if (rows.isEmpty() || !encoder.matches(r.password(), (String) rows.get(0).get("password_hash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        UUID userId = (UUID) rows.get(0).get("id");
        Member m = membership(userId, null)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "No shop access for this user"));
        return issueTokens(userId, m.tenantId(), m.role());
    }

    /** Rotation: the old token is revoked in the same statement that reads it. One use only. */
    public TokenResponse refresh(RefreshRequest r) {
        List<RefreshRow> rows = jdbc.query("""
                update refresh_tokens set revoked_at = now()
                where token_hash = ? and revoked_at is null and expires_at > now()
                returning user_id, tenant_id""",
                (rs, i) -> new RefreshRow(rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                sha256(r.refreshToken()));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        RefreshRow row = rows.get(0);
        Member m = membership(row.userId(), row.tenantId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Access removed"));
        return issueTokens(row.userId(), row.tenantId(), m.role());
    }

    public void logout(RefreshRequest r) {
        jdbc.update("update refresh_tokens set revoked_at = now() where token_hash = ? and revoked_at is null",
                sha256(r.refreshToken()));
    }

    private Optional<Member> membership(UUID userId, UUID tenantIdOrNull) {
        return jdbc.query("select tenant_id, role from login_memberships(?)",
                        (rs, i) -> new Member(rs.getObject("tenant_id", UUID.class), rs.getString("role")), userId)
                .stream()
                .filter(m -> tenantIdOrNull == null || m.tenantId().equals(tenantIdOrNull))
                .findFirst();
    }

    private TokenResponse issueTokens(UUID userId, UUID tenantId, String role) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("insert into refresh_tokens(id, user_id, tenant_id, token_hash, expires_at) values (?,?,?,?,?)",
                UUID.randomUUID(), userId, tenantId, sha256(refresh),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(refreshDays));
        return new TokenResponse(jwt.createAccessToken(userId, tenantId, role), refresh,
                jwt.accessTtlSeconds(), tenantId, role);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
