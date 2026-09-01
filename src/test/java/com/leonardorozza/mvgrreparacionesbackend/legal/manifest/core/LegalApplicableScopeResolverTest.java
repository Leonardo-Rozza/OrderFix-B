package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalApplicableScopeResolverTest {

    @Test
    void freezesTheTwoServerOwnedProfiles() {
        assertThat(PerfilAgregadoLegal.values()).containsExactly(
                PerfilAgregadoLegal.REGISTRATION,
                PerfilAgregadoLegal.AUTHENTICATED_PENDING);
    }

    @Test
    void defaultPolicyResolvesTheRegistrationProfileExactly() {
        LegalApplicableScopeSet scopes = new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR);

        assertThat(scopes.profile()).isEqualTo(PerfilAgregadoLegal.REGISTRATION);
        assertThat(scopes.audience()).isEqualTo(AudienciaLegal.ADMIN_TITULAR);
        assertThat(scopes.contexts()).containsExactly(ContextoLegal.REGISTRO);
    }

    @Test
    void defaultPolicyProvidesTheAuthenticatedBaselineForBothCurrentAudiences() {
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver();

        assertThat(resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER).contexts())
                .containsExactly(ContextoLegal.USO_CONTINUADO);
        assertThat(resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR).contexts())
                .containsExactly(ContextoLegal.USO_CONTINUADO);
    }

    @Test
    void rejectsRegistrationForAWorkerAudience() {
        assertThatThrownBy(() -> new LegalApplicableScopeResolver().resolve(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN_TITULAR");
    }

    @Test
    void injectedServerPolicyCanAddEveryKnownContextAndResolverCanonicalizesThem() {
        LegalApplicabilityPolicy fixture = (profile, audience) -> {
            ArrayList<ContextoLegal> reversed = new ArrayList<>(
                    Arrays.asList(ContextoLegal.values()));
            java.util.Collections.reverse(reversed);
            return reversed;
        };
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver(fixture);

        LegalApplicableScopeSet scopes = resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER);

        assertThat(scopes.contexts()).containsExactly(ContextoLegal.values());
    }

    @Test
    void rejectsInvalidPolicyOutputsInsteadOfReducingOrRepairingThem() {
        assertThatThrownBy(() -> resolverReturning(List.of()).resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolverReturning(List.of(ContextoLegal.ATESTACION_FOTOS)).resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USO_CONTINUADO");
    }

    @Test
    void validatesAuthorityInputsBeforeCallingThePolicy() {
        LegalApplicabilityPolicy mustNotRun = (profile, audience) -> {
            throw new AssertionError("policy must not run");
        };
        LegalApplicableScopeResolver resolver = new LegalApplicableScopeResolver(mustNotRun);

        assertThatThrownBy(() -> resolver.resolve(
                null,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                null,
                AudienciaLegal.USER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.resolve(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LegalApplicableScopeResolver(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static LegalApplicableScopeResolver resolverReturning(List<ContextoLegal> contexts) {
        return new LegalApplicableScopeResolver((profile, audience) -> contexts);
    }
}
