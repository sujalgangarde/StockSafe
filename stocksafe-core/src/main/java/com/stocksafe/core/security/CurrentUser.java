package com.stocksafe.core.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The logged-in user id for this request, or null on background threads. */
public final class CurrentUser {
    private CurrentUser() {}

    public static UUID id() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof AuthPrincipal p) ? p.userId() : null;
    }
}
