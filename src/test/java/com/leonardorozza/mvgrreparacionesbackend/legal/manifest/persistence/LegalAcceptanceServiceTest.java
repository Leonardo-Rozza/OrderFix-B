package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalAcceptanceServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LegalAcceptanceTransactionBoundary boundary = mock(LegalAcceptanceTransactionBoundary.class);
    private final LegalRequiredSetAggregateStore aggregates = mock(LegalRequiredSetAggregateStore.class);
    private final LegalPrivateRequirementsReader requirements = mock(LegalPrivateRequirementsReader.class);
    private final LegalAcceptanceEvidenceReader evidence = mock(LegalAcceptanceEvidenceReader.class);
    private final LegalV29AcceptanceSchemaVerifier schema = mock(LegalV29AcceptanceSchemaVerifier.class);
    private final LegalAcceptanceKeyConfiguration keys = mock(LegalAcceptanceKeyConfiguration.class);

    @ParameterizedTest @ValueSource(strings = {"boundary", "aggregate", "requirements", "evidence", "schema"})
    void rejectsCollaboratorsUsingAnotherJdbcBeforeOpeningAnyConnection(String foreign) {
        setup();
        switch (foreign) {
            case "boundary" -> when(boundary.usesJdbc(jdbc)).thenReturn(false);
            case "aggregate" -> when(aggregates.usesJdbc(jdbc)).thenReturn(false);
            case "requirements" -> when(requirements.usesJdbc(jdbc)).thenReturn(false);
            case "evidence" -> when(evidence.usesJdbc(jdbc)).thenReturn(false);
            case "schema" -> when(schema.usesJdbc(jdbc)).thenReturn(false);
        }
        assertThatThrownBy(this::construct).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
    }

    @Test void missingPrincipalFailsWithoutBorrowingOrConsultingTheDatabase() {
        setup(); var service = construct(); clearInvocations(jdbc, boundary);
        Throwable rejected = catchThrowable(() -> service.accept(null, "invalid-key", "invalid-revision", null, metadata()));
        assertFailure(rejected, LegalAcceptanceFailure.Reason.INVALID_ACTOR);
        verifyNoInteractions(jdbc, boundary);
    }

    @Test void disabledPrincipalCannotReachFingerprintOrAnyDatabaseBoundary() {
        setup(); var service = construct(); clearInvocations(jdbc, boundary);
        var actor = principal(false);
        Throwable rejected = catchThrowable(() -> service.accept(actor, "invalid-key", "invalid-revision", null, metadata()));
        assertFailure(rejected, LegalAcceptanceFailure.Reason.INVALID_ACTOR);
        verifyNoInteractions(jdbc, boundary);
    }

    @Test void missingCaptureFailsClosedBeforeAnyBusinessOperation() {
        setup(); var service = construct(); clearInvocations(jdbc, boundary);
        Throwable rejected = catchThrowable(() -> service.accept(principal(true), UUID.randomUUID().toString(),
                "sha256:" + "0".repeat(64), List.of(), null));
        assertFailure(rejected, LegalAcceptanceFailure.Reason.UNAVAILABLE);
        verifyNoInteractions(jdbc, boundary);
    }

    @Test void receiptKeepsItsIdsImmutableAndDiagnosticsExcludeIdentity() {
        UUID lot = UUID.randomUUID(), act = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>(List.of(act));
        var receipt = new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.WITH_ACTS, false, lot, ids);
        ids.clear(); assertThat(receipt.acceptanceIds()).containsExactly(act);
        assertThatThrownBy(() -> receipt.acceptanceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(receipt.toString()).doesNotContain(lot.toString(), act.toString());
        assertThatThrownBy(() -> new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.EMPTY, false, lot, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.DEDUP, false, null, List.of(act, act)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void explicitContextBuildsTheCompleteServiceWithoutOpeningAConnection() {
        com.zaxxer.hikari.HikariDataSource pool;
        try (var context = LegalAcceptanceDatabaseConfigurationTest.context(
                LegalAcceptanceDatabaseConfigurationTest.properties())) {
            context.refresh();
            var service = context.getBean(LegalAcceptanceService.class);
            pool = context.getBean(com.zaxxer.hikari.HikariDataSource.class);
            assertThat(service).isNotNull();
            assertThat(context.getBeansOfType(LegalAcceptanceService.class)).hasSize(1);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
            assertFailure(catchThrowable(() -> service.accept(null, "invalid", "invalid", null, metadata())),
                    LegalAcceptanceFailure.Reason.INVALID_ACTOR);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
        }
        assertThat(pool.isClosed()).isTrue();
    }

    private void setup() {
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        when(boundary.usesJdbc(jdbc)).thenReturn(true); when(aggregates.usesJdbc(jdbc)).thenReturn(true);
        when(requirements.usesJdbc(jdbc)).thenReturn(true); when(evidence.usesJdbc(jdbc)).thenReturn(true);
        when(schema.usesJdbc(jdbc)).thenReturn(true); when(schema.expectedSchema()).thenReturn("public");
        String hmac = Base64.getEncoder().encodeToString("1".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String aes = Base64.getEncoder().encodeToString("3".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        when(keys.keyring()).thenReturn(new LegalIdempotencyKeyring(Map.of(1, hmac), 1, Duration.ofHours(25)));
        when(keys.codec()).thenReturn(new LegalAcceptanceMetadataCodec(Map.of(7, aes), 7));
        when(keys.retentionPolicy()).thenReturn(new LegalAcceptanceMetadataPolicy(Duration.ofDays(30)));
    }

    private LegalAcceptanceService construct() {
        return new LegalAcceptanceService(jdbc, boundary, new LegalApplicableScopeResolver(), aggregates,
                requirements, evidence, schema, keys);
    }

    private static void assertFailure(Throwable failure, LegalAcceptanceFailure.Reason reason) {
        assertThat(failure).isInstanceOf(LegalAcceptanceFailure.class);
        var typed = (LegalAcceptanceFailure) failure;
        assertThat(typed.reason()).isEqualTo(reason);
        assertThat(typed.completion()).isEqualTo(LegalAcceptanceFailure.Completion.NONE);
        assertThat(typed.persistence()).isEqualTo(LegalAcceptanceFailure.Persistence.NOT_PERSISTED);
        assertThat(typed.confirmedReceipt()).isEmpty();
    }

    private static AuthenticatedUserPrincipal principal(boolean active) {
        Taller workshop = new Taller(); workshop.setId(2L);
        User user = new User(); user.setId(1L); user.setTaller(workshop); user.setRole(UserRole.ADMIN);
        user.setActive(active); user.setTokenVersion(0L); user.setEmail("synthetic@example.test"); user.cambiarPassword("synthetic");
        return new AuthenticatedUserPrincipal(user);
    }

    private static LegalRequestMetadata metadata() {
        return LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral("192.0.2.15"), "Synthetic agent");
    }
}
