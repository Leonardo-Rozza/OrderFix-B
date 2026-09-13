package com.leonardorozza.mvgrreparacionesbackend.config.tenant;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TenantServiceClosureTests {
    private final TallerRepository talleres = mock(TallerRepository.class);
    private final WorkshopClosureGate closure = mock(WorkshopClosureGate.class);
    private final TenantService service = new TenantService(talleres, closure);

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void operationalIdentityRequiresThePersistedWorkshopGate() {
        TenantContext.setTallerId(7L);
        assertThat(service.currentTallerId()).isEqualTo(7L);
        verify(closure).requireOperational(7L);
    }

    @Test
    void closedWorkshopCannotAcquireAReferenceForAWrite() {
        TenantContext.setTallerId(7L);
        doThrow(new WorkshopClosureBlockedException()).when(closure).requireOperational(7L);
        assertThatThrownBy(service::currentTallerRef).isInstanceOf(WorkshopClosureBlockedException.class);
        verifyNoInteractions(talleres);
    }

    @Test
    void restrictedAccountIdentityDoesNotAuthorizeOperationalAccess() {
        TenantContext.setTallerId(7L);
        assertThat(service.currentTallerIdForAccount()).isEqualTo(7L);
        verifyNoInteractions(closure, talleres);
        doThrow(new WorkshopClosureBlockedException()).when(closure).requireOperational(7L);
        assertThatThrownBy(service::currentTallerId).isInstanceOf(WorkshopClosureBlockedException.class);
    }

    @Test
    void missingTenantStillFailsForBothAccountAndOperations() {
        TenantContext.clear();
        assertThatThrownBy(service::currentTallerIdForAccount).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(service::currentTallerId).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(closure, talleres);
    }
}
