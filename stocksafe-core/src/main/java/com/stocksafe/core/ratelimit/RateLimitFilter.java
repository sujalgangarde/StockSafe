package com.stocksafe.core.ratelimit;

import com.stocksafe.core.apikey.ApiKeyFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Login/signup: 30 per minute per IP. Public API: 120 per minute per key. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "stocksafe.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean ok = true;
        if (path.startsWith("/api/v1/auth/")) {
            ok = limiter.allow("auth:" + req.getRemoteAddr(), 30, 60);
        } else if (path.startsWith("/api/v1/public/")) {
            String key = req.getHeader("X-API-Key");
            ok = limiter.allow("pub:" + (key == null ? req.getRemoteAddr() : ApiKeyFilter.sha256(key).substring(0, 16)), 120, 60);
        }
        if (!ok) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":429,\"detail\":\"Too many requests. Try again in a minute.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
