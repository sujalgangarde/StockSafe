package com.stocksafe.alerts.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/** Holds the current tenant id for this thread. */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) { CURRENT.set(tenantId); }

    public static UUID get() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) throw new IllegalStateException("No tenant in context");
        return id;
    }

    /** Run work as a tenant, then restore the previous value. Open transactions INSIDE the work. */
    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
