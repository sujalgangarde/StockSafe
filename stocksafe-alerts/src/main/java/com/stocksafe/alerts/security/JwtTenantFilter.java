package com.stocksafe.alerts.security;

import com.stocksafe.alerts.tenant.TenantContext;
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

public class JwtTenantFilter extends OncePerRequestFilter {
    private final JwtVerifier jwt;

    public JwtTenantFilter(JwtVerifier jwt) {
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
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role()))));
                    TenantContext.set(p.tenantId());
                    MDC.put("tenantId", p.tenantId().toString());
                } catch (JwtException | IllegalArgumentException e) {
                    // bad token: stay anonymous
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
        }
    }
}
