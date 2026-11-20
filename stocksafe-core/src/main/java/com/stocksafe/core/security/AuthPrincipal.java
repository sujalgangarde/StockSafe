package com.stocksafe.core.security;

import java.util.UUID;

/** Who is calling. Built from the JWT. */
public record AuthPrincipal(UUID userId, UUID tenantId, String role) {}
