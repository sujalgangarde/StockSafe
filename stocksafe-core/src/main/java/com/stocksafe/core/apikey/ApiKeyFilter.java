package com.stocksafe.core.apikey;

import com.stocksafe.core.security.AuthPrincipal;
import com.stocksafe.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** For /api/v1/public/**: X-API-Key header -> tenant. The caller gets role API (read only). */
public class ApiKeyFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;

    public ApiKeyFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/v1/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        try {
            if (key != null && !key.isBlank()) {
                UUID tenant = jdbc.queryForList("select api_key_tenant(?)", UUID.class, sha256(key))
                        .stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (tenant != null) {
                    var auth = new UsernamePasswordAuthenticationToken(new AuthPrincipal(null, tenant, "API"), null,
                            List.of(new SimpleGrantedAuthority("ROLE_API")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(tenant);
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
