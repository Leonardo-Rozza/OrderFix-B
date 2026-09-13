package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

class WorkshopClosureAccessTest {
    private static final Instant CONFIRMED = Instant.parse("2026-09-12T14:00:00.123456Z");
    private static final Instant END = CONFIRMED.plus(WorkshopClosurePolicy.RESTORATION_WINDOW);

    @ParameterizedTest @EnumSource(UserRole.class)
    void openWorkshopPreservesSoftEmailVerificationAndRoles(UserRole role) {
        User actor = actor();
        actor.setRole(role);
        actor.setEmailVerificado(false);
        actor.getTaller().setCierreEstado("ABIERTO");
        assertThat(WorkshopClosureAccess.mode(actor, CONFIRMED)).isEqualTo(WorkshopClosureAccess.Mode.OPERATIVE);
        assertThat(new AuthenticatedUserPrincipal(actor, CONFIRMED).isEnabled()).isTrue();
    }

    @Test void graceIncludesConfirmationButExcludesItsDeadline() {
        User actor = actor();
        assertThat(WorkshopClosureAccess.mode(actor, CONFIRMED.minusNanos(1))).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
        assertThat(WorkshopClosureAccess.mode(actor, CONFIRMED)).isEqualTo(WorkshopClosureAccess.Mode.RESTRICTED);
        assertThat(WorkshopClosureAccess.mode(actor, END.minusNanos(1))).isEqualTo(WorkshopClosureAccess.Mode.RESTRICTED);
        assertThat(WorkshopClosureAccess.mode(actor, END)).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
        assertThat(WorkshopClosureAccess.mode(actor, END.plusSeconds(1))).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
    }

    @ParameterizedTest @EnumSource(InvalidState.class)
    void malformedOrIneligibleCurrentStateNeverCreatesAnEnabledPrincipal(InvalidState invalid) {
        User actor = actor();
        invalid.change.accept(actor);
        assertThat(WorkshopClosureAccess.mode(actor, CONFIRMED)).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
        assertThat(new AuthenticatedUserPrincipal(actor, CONFIRMED).isEnabled()).isFalse();
    }

    @Test void inactiveWorkshopRemainsAVetoEvenWhenLifecycleIsOpen() {
        User actor = actor();
        actor.getTaller().setCierreEstado("ABIERTO");
        actor.getTaller().setActivo(false);
        assertThat(WorkshopClosureAccess.mode(actor, CONFIRMED)).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
        assertThat(WorkshopClosureAccess.mode(null, CONFIRMED)).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
        assertThat(WorkshopClosureAccess.mode(actor(), null)).isEqualTo(WorkshopClosureAccess.Mode.DENIED);
    }

    @Test void restrictedPrincipalRetainsOnlyIdentityAndTheCurrentRevocationEpoch() {
        User actor = actor();
        var principal = new AuthenticatedUserPrincipal(actor, CONFIRMED);
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isWorkshopRestricted()).isTrue();
        assertThat(principal.getUserId()).isEqualTo(10L);
        assertThat(principal.getTallerId()).isEqualTo(20L);
        assertThat(principal.getTokenVersion()).isEqualTo(7L);
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    private enum InvalidState {
        EMPLOYEE(u -> u.setRole(UserRole.USER)), UNVERIFIED(u -> u.setEmailVerificado(false)),
        USER_INACTIVE(u -> u.setActive(false)), WORKSHOP_INACTIVE(u -> u.getTaller().setActivo(false)),
        ELIMINATED(u -> u.getTaller().setCierreEstado("ELIMINADO")), UNKNOWN(u -> u.getTaller().setCierreEstado("unknown")),
        MISSING_STATE(u -> u.getTaller().setCierreEstado(null)), MISSING_REFERENCE(u -> u.getTaller().setCierreReferencia(null)),
        ZERO_EPOCH(u -> u.getTaller().setCierreVersion(0)), NEGATIVE_USER_EPOCH(u -> u.setTokenVersion(-1)),
        MISSING_CONFIRMATION(u -> u.getTaller().setCierreConfirmadoEn(null)),
        WRONG_GRACE(u -> u.getTaller().setCierreReversibleHasta(END.plusSeconds(1).atOffset(ZoneOffset.UTC))),
        WRONG_PROCESSING(u -> u.getTaller().setCierreEliminacionPrevistaEn(END.atOffset(ZoneOffset.UTC))),
        NON_MICROSECOND(u -> u.getTaller().setCierreConfirmadoEn(CONFIRMED.plusNanos(1).atOffset(ZoneOffset.UTC)));
        final Consumer<User> change;
        InvalidState(Consumer<User> change) { this.change = change; }
    }

    private static User actor() {
        Taller workshop = Taller.builder().id(20L).nombre("Taller fixture").activo(true).build();
        var schedule = WorkshopClosurePolicy.scheduleAt(CONFIRMED);
        workshop.setCierreEstado("RESTRINGIDO"); workshop.setCierreVersion(1);
        workshop.setCierreReferencia(UUID.fromString("4b306acb-ce32-47ca-b18d-dce56c3ed255"));
        workshop.setCierreConfirmadoEn(schedule.confirmedAt().atOffset(ZoneOffset.UTC));
        workshop.setCierreReversibleHasta(schedule.reversibleUntil().atOffset(ZoneOffset.UTC));
        workshop.setCierreEliminacionPrevistaEn(schedule.deletionExpectedBy().atOffset(ZoneOffset.UTC));
        return User.builder().id(10L).username("Titular fixture").email("closure@example.test").password("fixture-hash")
                .role(UserRole.ADMIN).active(true).emailVerificado(true).tokenVersion(7).taller(workshop).build();
    }
}
