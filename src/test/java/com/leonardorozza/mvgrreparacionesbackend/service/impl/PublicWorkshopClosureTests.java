package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBusyException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.PresupuestoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PublicWorkshopClosureTests {
    private final ReparacionRepository repairs = mock(ReparacionRepository.class);
    private final PresupuestoRepository budgets = mock(PresupuestoRepository.class);
    private final TenantService tenant = mock(TenantService.class);
    private final WorkshopClosureGate closure = mock(WorkshopClosureGate.class);
    private final PresupuestoService service = new PresupuestoService(budgets, repairs, tenant, closure);
    private final PresupuestoService publicBudgets = mock(PresupuestoService.class);
    private final SeguimientoService tracking = new SeguimientoService(repairs, publicBudgets, closure);

    @Test
    void closedTrackingRevealsNeitherWorkshopNorBudget() {
        when(repairs.findByCodigoSeguimientoAndTallerActivoTrue("synthetic-code"))
                .thenReturn(Optional.of(repair()));
        doThrow(new WorkshopClosureBlockedException()).when(closure).requireOperational(7L);
        assertThatThrownBy(() -> tracking.consultar("synthetic-code"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No encontramos una reparación con ese código.");
        verifyNoInteractions(publicBudgets);
    }

    @Test
    void openTrackingGatesBeforeReadingThePublicBudget() {
        when(repairs.findByCodigoSeguimientoAndTallerActivoTrue("synthetic-code"))
                .thenReturn(Optional.of(repair()));
        assertThat(tracking.consultar("synthetic-code").taller()).isEqualTo("Taller de prueba");
        var order = inOrder(closure, publicBudgets);
        order.verify(closure).requireOperational(7L);
        order.verify(publicBudgets).ultimoDeReparacion(21L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closedWorkshopRejectsPublicBudgetResponsesBeforeLoadingMutableData(boolean approve) {
        when(repairs.findByCodigoSeguimientoAndTallerActivoTrue("synthetic-code"))
                .thenReturn(Optional.of(repair()));
        doThrow(new WorkshopClosureBlockedException()).when(closure).requireOperational(7L);
        assertThatThrownBy(() -> service.responderPorCodigo("synthetic-code", approve))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No encontramos una reparación con ese código.");
        verifyNoInteractions(budgets, tenant);
        verify(repairs, never()).save(any());
    }

    @Test
    void directPublicBudgetReadAlsoChecksThePersistedWorkshop() {
        when(repairs.findById(21L)).thenReturn(Optional.of(repair()));
        doThrow(new WorkshopClosureBlockedException()).when(closure).requireOperational(7L);
        assertThatThrownBy(() -> service.ultimoDeReparacion(21L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(budgets, tenant);
    }

    @Test
    void openWorkshopWithoutBudgetPreservesTheEmptyPublicResult() {
        when(repairs.findById(21L)).thenReturn(Optional.of(repair()));
        when(budgets.findFirstByReparacionIdOrderByCreatedAtDesc(21L)).thenReturn(Optional.empty());
        assertThat(service.ultimoDeReparacion(21L)).isNull();
        var order = inOrder(closure, budgets);
        order.verify(closure).requireOperational(7L);
        order.verify(budgets).findFirstByReparacionIdOrderByCreatedAtDesc(21L);
    }

    @Test
    void busyGateIsNotDisguisedAsMissingTracking() {
        when(repairs.findByCodigoSeguimientoAndTallerActivoTrue("synthetic-code"))
                .thenReturn(Optional.of(repair()));
        doThrow(new WorkshopClosureBusyException()).when(closure).requireOperational(7L);
        assertThatThrownBy(() -> tracking.consultar("synthetic-code")).isInstanceOf(WorkshopClosureBusyException.class);
        verifyNoInteractions(publicBudgets);
    }

    @Test
    void busyGateCannotAdmitAPublicBudgetResponse() {
        when(repairs.findByCodigoSeguimientoAndTallerActivoTrue("synthetic-code"))
                .thenReturn(Optional.of(repair()));
        doThrow(new WorkshopClosureBusyException()).when(closure).requireOperational(7L);
        assertThatThrownBy(() -> service.responderPorCodigo("synthetic-code", true))
                .isInstanceOf(WorkshopClosureBusyException.class);
        verifyNoInteractions(budgets, tenant);
        verify(repairs, never()).save(any());
    }

    private static Reparacion repair() {
        return Reparacion.builder().id(21L).codigoSeguimiento("synthetic-code")
                .taller(Taller.builder().id(7L).nombre("Taller de prueba").build())
                .equipo(Equipo.builder().id(31L).marca("Marca").modelo("Modelo").build()).build();
    }
}
