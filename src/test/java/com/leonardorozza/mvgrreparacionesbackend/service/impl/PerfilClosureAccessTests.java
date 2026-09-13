package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureAccess;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePolicy;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PerfilClosureAccessTests {
    private static final Instant NOW = Instant.parse("2026-09-12T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UserRepository users = mock(UserRepository.class);
    private final TenantService tenant = mock(TenantService.class);
    private final PerfilService service = new PerfilService(users, tenant, CLOCK);

    @Test
    void operativeAccountProfileRemainsAvailableWithoutAnOperationalGate() {
        User user = actor(UserRole.ADMIN);
        var principal = currentIdentity(user);

        assertThat(service.obtener(principal).taller().nombre()).isEqualTo("Taller de prueba");
        assertThat(service.obtener(principal).accesoTaller()).isEqualTo(WorkshopClosureAccess.Mode.OPERATIVE);
        verify(tenant, never()).currentTallerId();
    }

    @Test
    void verifiedAdminWithCurrentEpochRetainsAccountProfileDuringGrace() {
        User user = actor(UserRole.ADMIN);
        restrict(user.getTaller());
        var principal = currentIdentity(user);
        assertThat(principal.isWorkshopRestricted()).isTrue();

        var profile = service.obtener(principal);
        assertThat(profile.usuario().id()).isEqualTo(11L);
        assertThat(profile.usuario().role()).isEqualTo(UserRole.ADMIN);
        assertThat(profile.taller().id()).isEqualTo(7L);
        assertThat(profile.accesoTaller()).isEqualTo(WorkshopClosureAccess.Mode.RESTRICTED);
        verify(tenant, never()).currentTallerId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void principalFromAnEarlierEpochCannotReadTheCurrentAccount(boolean restricted) {
        User user = actor(UserRole.ADMIN);
        var principal = currentIdentity(user);
        user.setTokenVersion(principal.getTokenVersion() + 1);
        if (restricted) restrict(user.getTaller());

        assertUnavailable(principal);
        verify(users).findPerfilByIdAndTallerId(11L, 7L);
    }

    @Test
    void employeeAdmittedBeforeClosureCannotReadProfileFromTheNewState() {
        User user = actor(UserRole.USER);
        var principal = currentIdentity(user);
        restrict(user.getTaller());
        assertThat(principal.getWorkshopAccessMode()).isEqualTo(WorkshopClosureAccess.Mode.OPERATIVE);
        assertThat(principal.getTokenVersion()).isEqualTo(user.getTokenVersion());

        assertUnavailable(principal);
    }

    @Test
    void restrictedPrincipalDoesNotPreserveAdminAccessAfterCurrentRoleChanges() {
        User user = actor(UserRole.ADMIN);
        restrict(user.getTaller());
        var principal = currentIdentity(user);
        user.setRole(UserRole.USER);
        assertThat(principal.isWorkshopRestricted()).isTrue();

        assertUnavailable(principal);
    }

    @Test
    void inactiveUserReadAfterAuthenticationCannotReturnAProfile() {
        User user = actor(UserRole.ADMIN);
        var principal = currentIdentity(user);
        user.setActive(false);

        assertUnavailable(principal);
    }

    @Test
    void accountProfileRechecksTheExactGraceDeadlineAfterReadingTheUser() {
        User user = actor(UserRole.ADMIN);
        restrict(user.getTaller());
        var principal = currentIdentity(user);
        var end = user.getTaller().getCierreReversibleHasta().toInstant();
        var later = new PerfilService(users, tenant, Clock.fixed(end, ZoneOffset.UTC));
        assertThat(principal.isWorkshopRestricted()).isTrue();

        assertThatThrownBy(() -> later.obtener(principal)).isExactlyInstanceOf(UnauthorizedException.class)
                .hasMessage("El usuario autenticado ya no está disponible.");
    }

    @Test
    void accountIdentityCannotCrossTheAuthenticatedTenant() {
        var principal = new AuthenticatedUserPrincipal(actor(UserRole.ADMIN), NOW);
        when(tenant.currentTallerIdForAccount()).thenReturn(8L);

        assertThatThrownBy(() -> service.obtener(principal)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(users);
    }

    private AuthenticatedUserPrincipal currentIdentity(User user) {
        when(tenant.currentTallerIdForAccount()).thenReturn(7L);
        when(users.findPerfilByIdAndTallerId(11L, 7L)).thenReturn(Optional.of(user));
        return new AuthenticatedUserPrincipal(user, NOW);
    }

    private void assertUnavailable(AuthenticatedUserPrincipal principal) {
        assertThatThrownBy(() -> service.obtener(principal)).isExactlyInstanceOf(UnauthorizedException.class)
                .hasMessage("El usuario autenticado ya no está disponible.");
    }

    private static User actor(UserRole role) {
        Taller taller = Taller.builder().id(7L).nombre("Taller de prueba").build();
        return User.builder().id(11L).taller(taller).username("Cuenta de prueba")
                .email("cuenta@example.test").password("synthetic-profile-hash").role(role)
                .active(true).emailVerificado(true).tokenVersion(4L).build();
    }

    private static void restrict(Taller taller) {
        var schedule = WorkshopClosurePolicy.scheduleAt(NOW.minusSeconds(60));
        taller.setCierreEstado("RESTRINGIDO");
        taller.setCierreVersion(1L);
        taller.setCierreReferencia(UUID.randomUUID());
        taller.setCierreConfirmadoEn(schedule.confirmedAt().atOffset(ZoneOffset.UTC));
        taller.setCierreReversibleHasta(schedule.reversibleUntil().atOffset(ZoneOffset.UTC));
        taller.setCierreEliminacionPrevistaEn(schedule.deletionExpectedBy().atOffset(ZoneOffset.UTC));
    }
}
