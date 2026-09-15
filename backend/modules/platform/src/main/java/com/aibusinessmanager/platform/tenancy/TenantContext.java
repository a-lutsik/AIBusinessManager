package com.aibusinessmanager.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Thread-local tenant scope. Set by HTTP filters for sync requests; must be passed
 * explicitly in async / scheduled payloads (ThreadLocal does not travel).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("TenantContext is not set"));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Scope open(UUID tenantId) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
