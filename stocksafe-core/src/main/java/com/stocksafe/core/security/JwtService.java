package com.stocksafe.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Creates and checks short-lived access tokens (HS256). */
@Service
public class JwtService {
    private final SecretKey key;
    private final long accessTtlMinutes;

    public JwtService(@Value("${stocksafe.jwt.secret}") String secret,
                      @Value("${stocksafe.jwt.access-ttl-minutes}") long accessTtlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public String createAccessToken(UUID userId, UUID tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /** Throws JwtException if the signature is bad or the token is expired. */
    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthPrincipal(
                UUID.fromString(c.getSubject()),
                UUID.fromString(c.get("tid", String.class)),
                c.get("role", String.class));
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}
