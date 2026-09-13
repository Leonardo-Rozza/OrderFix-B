package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalRestrictedMaintenanceRoleFixtureTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ordenfix", "production", "ordenfix_legal_acceptance_fixture", "ordenfix_legal_maintenance"})
    void rejectsAnyDatabaseOutsideTheDedicatedPrefixWithoutMutation(String database) {
        JdbcTemplate owner = mock(JdbcTemplate.class);
        when(owner.queryForObject("SELECT pg_catalog.current_database()", String.class)).thenReturn(database);
        assertThatThrownBy(() -> LegalRestrictedMaintenanceRoleFixture.requireSafeEphemeralDatabase(owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El fixture de mantenimiento sólo puede mutar una base efímera dedicada");
        verify(owner).queryForObject("SELECT pg_catalog.current_database()", String.class);
        verifyNoMoreInteractions(owner);
    }

    @Test void syntheticCredentialsAreNeverRenderedByToString() {
        var credentials = new LegalRestrictedMaintenanceRoleFixture.Credentials(
                "jdbc:postgresql://localhost/synthetic", "synthetic_role", "synthetic_password", "org.postgresql.Driver");
        assertThat(credentials.toString()).isEqualTo("Credentials[configured=true]");
    }

    @Test void verifierRejectsMissingIdentityAndKeepsTheExactJdbcBoundary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new LegalAcceptanceMaintenancePrivilegeVerifier(jdbc, " ", "public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalAcceptanceMaintenancePrivilegeVerifier(jdbc, "nominal", " "))
                .isInstanceOf(IllegalArgumentException.class);
        var verifier = new LegalAcceptanceMaintenancePrivilegeVerifier(jdbc, " nominal ", " public ");
        assertThat(verifier.expectedRole()).isEqualTo("nominal");
        assertThat(verifier.expectedSchema()).isEqualTo("public");
        assertThat(verifier.usesJdbc(jdbc)).isTrue();
        assertThat(verifier.usesJdbc(mock(JdbcTemplate.class))).isFalse();
        verifyNoInteractions(jdbc);
    }
}
