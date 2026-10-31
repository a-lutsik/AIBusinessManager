package com.cadence.catalog.internal;

import com.cadence.catalog.api.SpecialistView;
import com.cadence.platform.error.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The race behind the check for a second sign-in link: the unique index answers, and the answer becomes a 409. */
class CatalogServiceImplTest {

    private static final SpecialistView LINKED = new SpecialistView(null, "Nino", "kc-1", "#0f766e", true);

    /** A repository where the link looks free and the insert then runs into {@code failure}. */
    private static CatalogServiceImpl failingWith(RuntimeException failure) {
        CatalogRepository repository = new CatalogRepository(null) {
            @Override
            Optional<SpecialistView> findSpecialistByKeycloakUserId(String keycloakUserId) {
                return Optional.empty();
            }

            @Override
            Optional<SpecialistView> findSpecialist(UUID id) {
                return Optional.empty();
            }

            @Override
            void upsertSpecialist(SpecialistView specialist) {
                throw failure;
            }
        };
        return new CatalogServiceImpl(repository, (actorId, actorType, action, entityType, entityId, before, after, source, requestId) -> {
        });
    }

    private static RuntimeException violation(String sqlState, String constraint) {
        return new DataIntegrityViolationException("translated", new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", sqlState));
    }

    @Test
    void theLinkIndexBecomesAlreadyLinked() {
        CatalogServiceImpl service = failingWith(violation("23505", "specialist_keycloak_user_uq"));

        DomainException refused = assertThrows(DomainException.class, () -> service.upsertSpecialist(LINKED));

        assertEquals("KEYCLOAK_USER_ALREADY_LINKED", refused.code());
        assertEquals(409, refused.status());
    }

    @Test
    void anotherViolationStaysWhatItIs() {
        RuntimeException otherIndex = violation("23505", "specialist_pk");
        assertSame(otherIndex, assertThrows(RuntimeException.class, () -> failingWith(otherIndex).upsertSpecialist(LINKED)));

        RuntimeException otherState = violation("23503", "specialist_keycloak_user_uq");
        assertSame(otherState, assertThrows(RuntimeException.class, () -> failingWith(otherState).upsertSpecialist(LINKED)));
    }
}
