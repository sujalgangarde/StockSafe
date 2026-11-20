package com.stocksafe.alerts.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Checks the access token issued by stocksafe-core (same secret). This service never issues tokens. */
@Service
public class JwtVerifier {
    private final SecretKey key;

    public JwtVerifier(@Value("${stocksafe.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthPrincipal(UUID.fromString(c.getSubject()), UUID.fromString(c.get("tid", String.class)),
                c.get("role", String.class));
    }
}
