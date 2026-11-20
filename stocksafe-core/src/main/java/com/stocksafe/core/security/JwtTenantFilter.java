package com.stocksafe.core.security;

import com.stocksafe.core.tenant.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the Bearer token, logs the user in, and sets the tenant for this request.
 * Not a @Component on purpose: SecurityConfig adds it to the security chain only.
 */
public class JwtTenantFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    public JwtTenantFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        try {
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    AuthPrincipal p = jwt.parse(header.substring(7));
                    var auth = new UsernamePasswordAuthenticationToken(
                            p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(p.tenantId());
                    MDC.put("tenantId", p.tenantId().toString());
                } catch (JwtException | IllegalArgumentException e) {
                    // Bad token: stay anonymous. Security returns 401.
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();   // never leak the tenant to the next request on this thread
            MDC.remove("tenantId");
        }
    }
}
