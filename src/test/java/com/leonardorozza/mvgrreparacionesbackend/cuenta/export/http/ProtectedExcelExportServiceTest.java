package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService.ExportSession;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.ExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TransactionTemplate is real; collaborators are doubles, while HTTP/PostgreSQL tests cover their wiring. */
class ProtectedExcelExportServiceTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void commitsOnlyAfterConsumingCheckingCapacityGeneratingAndReauthorizing() {
        var f = new Fixture();

        assertThat(f.service.download("access", "proof")).isSameAs(f.bytes);

        var order = inOrder(f.manager, f.reauth, f.jdbc, f.excel);
        order.verify(f.manager).getTransaction(any());
        order.verify(f.reauth).authorize("access");
        order.verify(f.jdbc).queryForObject(anyString(), eq(Boolean.class), eq(2L));
        order.verify(f.reauth).consume("access", "proof", ExportReauthenticationPurpose.DESCARGAR_EXPORTACION);
        order.verify(f.jdbc, times(10)).queryForObject(anyString(), Fixture.<ProtectedExcelExportService.Footprint>mapper(), any(Object[].class));
        order.verify(f.excel).exportarExcel();
        order.verify(f.reauth).authorize("access");
        order.verify(f.manager).commit(f.status);
        verify(f.manager, never()).rollback(any());
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(f.manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(definition.getValue().getTimeout()).isEqualTo(30);
    }

    @Test void mismatchedTenantRejectsBeforeConsumingOrReadingWorkshopData() {
        var f = new Fixture();
        TenantContext.setTallerId(9L);

        assertCode(f, ExportPackageException.Code.ACCESS_DENIED);

        verifyNoInteractions(f.jdbc, f.excel);
        verify(f.reauth, never()).consume(anyString(), anyString(), any());
        verify(f.manager).rollback(f.status);
    }

    @Test void inactiveWorkshopRejectsBeforeConsumption() {
        var f = new Fixture();
        when(f.jdbc.queryForObject(anyString(), eq(Boolean.class), eq(2L))).thenReturn(false);

        assertCode(f, ExportPackageException.Code.ACCESS_DENIED);

        verifyNoInteractions(f.excel);
        verify(f.reauth, never()).consume(anyString(), anyString(), any());
        verify(f.manager).rollback(f.status);
    }

    @Test void invalidProofDoesNotStartWorkbookGeneration() {
        var f = new Fixture();
        var invalid = new BadRequestException("REAUTENTICACION_INVALIDA", "Prueba inválida");
        doThrow(invalid).when(f.reauth).consume("access", "proof", ExportReauthenticationPurpose.DESCARGAR_EXPORTACION);

        assertThatThrownBy(() -> f.service.download("access", "proof")).isSameAs(invalid);

        verifyNoInteractions(f.excel);
        verify(f.manager).rollback(f.status);
    }

    @Test void rowCapacityIsCheckedBeforePoiAndRollsBackTheProof() {
        var f = new Fixture();
        f.footprint(new ProtectedExcelExportService.Footprint(50_001, 20, true));

        assertCode(f, ExportPackageException.Code.CAPACITY_EXCEEDED);

        verifyNoInteractions(f.excel);
        verify(f.jdbc).queryForObject(anyString(), Fixture.<ProtectedExcelExportService.Footprint>mapper(), any(Object[].class));
        verify(f.manager).rollback(f.status);
    }

    @Test void accumulatedContentCapacityRejectsWithoutHydratingRows() {
        var f = new Fixture();
        f.footprint(new ProtectedExcelExportService.Footprint(1, ProtectedExcelExportService.MAX_SOURCE_BYTES / 2 + 1, true));

        assertCode(f, ExportPackageException.Code.CAPACITY_EXCEEDED);

        verifyNoInteractions(f.excel);
        verify(f.jdbc, times(2)).queryForObject(anyString(), Fixture.<ProtectedExcelExportService.Footprint>mapper(), any(Object[].class));
        verify(f.manager).rollback(f.status);
    }

    @Test void inconsistentRelationsNeverReachTheExistingUnscopedCalculations() {
        var f = new Fixture();
        f.footprint(new ProtectedExcelExportService.Footprint(1, 20, false));

        assertCode(f, ExportPackageException.Code.INCONSISTENT_RELATION);

        verifyNoInteractions(f.excel);
        verify(f.manager).rollback(f.status);
    }

    @Test void generationFailureRollsBackAndDoesNotExposeItsCause() {
        var f = new Fixture();
        when(f.excel.exportarExcel()).thenThrow(new IllegalStateException("driver secret and private data"));

        assertCode(f, ExportPackageException.Code.UNAVAILABLE);

        verify(f.reauth).authorize("access");
        verify(f.manager).rollback(f.status);
        verify(f.manager, never()).commit(any());
    }

    @Test void expiredOrRevokedSessionAfterGenerationErasesTheUnreleasedWorkbook() {
        var f = new Fixture();
        when(f.reauth.authorize("access")).thenReturn(f.actor).thenThrow(new UnauthorizedException("Sesión vencida"));

        assertThatThrownBy(() -> f.service.download("access", "proof")).isInstanceOf(UnauthorizedException.class);

        assertThat(f.bytes).containsOnly((byte) 0);
        verify(f.manager).rollback(f.status);
        verify(f.manager, never()).commit(any());
    }

    @Test void changedBindingAfterGenerationErasesTheUnreleasedWorkbook() {
        var f = new Fixture();
        when(f.reauth.authorize("access")).thenReturn(f.actor).thenReturn(new ExportSession(1, 2, 4, "session"));

        assertCode(f, ExportPackageException.Code.ACCESS_DENIED);

        assertThat(f.bytes).containsOnly((byte) 0);
        verify(f.manager).rollback(f.status);
    }

    @Test void excessiveOutputIsErasedAndItsProofIsRolledBack() {
        var f = new Fixture();
        f.service = new ProtectedExcelExportService(f.reauth, f.excel, f.jdbc, f.manager, 3);

        assertCode(f, ExportPackageException.Code.CAPACITY_EXCEEDED);

        assertThat(f.bytes).containsOnly((byte) 0);
        verify(f.manager).rollback(f.status);
    }

    @Test void commitFailureNeverReturnsTheWorkbookOrExposesDriverDiagnostics() {
        var f = new Fixture();
        doThrow(new TransactionSystemException("driver secret and private data")).when(f.manager).commit(f.status);

        assertCode(f, ExportPackageException.Code.UNAVAILABLE);

        assertThat(f.bytes).containsOnly((byte) 0);
        verify(f.manager).commit(f.status);
    }

    @Test void closureRejectionIsPreservedBeforeAnyProofOrWorkbookWork() {
        var f=new Fixture();
        var denied=new com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException();
        when(f.reauth.authorize("access")).thenThrow(denied);
        assertThatThrownBy(()->f.service.download("access","proof")).isSameAs(denied);
        verifyNoInteractions(f.jdbc,f.excel);
        verify(f.reauth,never()).consume(anyString(),anyString(),any());
        verify(f.manager).rollback(f.status);
    }

    @Test void closureAdmissionFailureAfterGenerationErasesBytesAndPreservesTheSafeException() {
        var f=new Fixture();
        var busy=new com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException();
        when(f.reauth.authorize("access")).thenReturn(f.actor).thenThrow(busy);
        assertThatThrownBy(()->f.service.download("access","proof")).isSameAs(busy);
        assertThat(f.bytes).containsOnly((byte)0);
        verify(f.manager).rollback(f.status);
        verify(f.manager,never()).commit(any());
    }

    private static void assertCode(Fixture f, ExportPackageException.Code code) {
        assertThatThrownBy(() -> f.service.download("access", "proof"))
                .isInstanceOfSatisfying(ExportPackageException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(error.getMessage()).doesNotContain("driver secret", "private data");
                });
    }

    private static final class Fixture {
        final ExportReauthenticationService reauth = mock(ExportReauthenticationService.class);
        final ExportService excel = mock(ExportService.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final SimpleTransactionStatus status = new SimpleTransactionStatus();
        final ExportSession actor = new ExportSession(1, 2, 3, "session");
        final byte[] bytes = {80, 75, 3, 4};
        ProtectedExcelExportService service;

        Fixture() {
            TenantContext.setTallerId(2L);
            when(manager.getTransaction(any())).thenReturn(status);
            when(reauth.authorize("access")).thenReturn(actor);
            when(excel.exportarExcel()).thenReturn(bytes);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(2L))).thenReturn(true);
            footprint(new ProtectedExcelExportService.Footprint(1, 20, true));
            service = new ProtectedExcelExportService(reauth, excel, jdbc, manager);
        }

        void footprint(ProtectedExcelExportService.Footprint footprint) {
            when(jdbc.queryForObject(anyString(), Fixture.<ProtectedExcelExportService.Footprint>mapper(), any(Object[].class)))
                    .thenReturn(footprint);
        }

        static <T> RowMapper<T> mapper() { return any(); }
    }
}
