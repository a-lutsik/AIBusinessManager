package com.aibusinessmanager.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped actor. Role is not enough: MASTER must also carry {@code specialistId}
 * so per-resource checks can reject foreign appointment/client ids.
 */
public final class ActorContext {

    public enum Role {
        OWNER,
        MASTER,
        PUBLIC,
        AI,
        SYSTEM
    }

    public record Actor(String actorId, Role role, UUID specialistId) {
        public boolean isOwner() {
            return role == Role.OWNER;
        }

        public boolean isMaster() {
            return role == Role.MASTER;
        }
    }

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorContext() {
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static Optional<Actor> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Actor require() {
        return current().orElseThrow(() -> new IllegalStateException("ActorContext is not set"));
    }

    public static Actor publicActor() {
        return new Actor("public", Role.PUBLIC, null);
    }

    public static Actor system() {
        return new Actor("system", Role.SYSTEM, null);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
