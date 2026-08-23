package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MetodoPago;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.CobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ReparacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerQrCobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.finanzas.CobroInvariantPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CobroServiceResumenDigitalTests {

    private static final long TALLER_ID = 31L;
    private static final long REPARACION_ID = 71L;

    @Mock
    private CobroRepository cobroRepository;

    @Mock
    private ReparacionRepository reparacionRepository;

    @Mock
    private TallerQrCobroRepository tallerQrCobroRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantService tenantService;

    @Mock
    private PlanFeatureService planFeatureService;

    @Mock
    private CobroInvariantPolicy cobroInvariantPolicy;

    @InjectMocks
    private CobroService cobroService;

    @Test
    void saldoCeroNoConsultaNiCargaElQr() {
        Reparacion reparacion = reparacion(true, "visible.mp");
        Cobro cobro = Cobro.builder()
                .monto(new BigDecimal("100"))
                .metodo(MetodoPago.EFECTIVO)
                .createdAt(LocalDateTime.of(2026, 8, 23, 10, 0))
                .build();
        preparar(reparacion, List.of(cobro));

        var resumen = cobroService.resumenDigital(REPARACION_ID);

        assertThat(resumen.importes().saldo()).isZero();
        assertThat(resumen.datosCobro()).isNull();
        verifyNoInteractions(tallerQrCobroRepository);
    }

    @Test
    void banderaOcultaNoConsultaNiCargaElQrAunqueHayaPendiente() {
        Reparacion reparacion = reparacion(false, "oculto.mp");
        preparar(reparacion, List.of());

        var resumen = cobroService.resumenDigital(REPARACION_ID);

        assertThat(resumen.importes().saldo()).isEqualByComparingTo("100");
        assertThat(resumen.datosCobro()).isNull();
        verifyNoInteractions(tallerQrCobroRepository);
    }

    @Test
    void pendienteVisibleConsultaSoloLaProyeccionSha() {
        Reparacion reparacion = reparacion(true, "visible.mp");
        preparar(reparacion, List.of());
        when(tallerQrCobroRepository.findSha256ByTallerId(TALLER_ID))
                .thenReturn(Optional.of("a".repeat(64)));

        var resumen = cobroService.resumenDigital(REPARACION_ID);

        assertThat(resumen.datosCobro()).isNotNull();
        assertThat(resumen.datosCobro().qrDisponible()).isTrue();
        assertThat(resumen.datosCobro().qrVersion()).isEqualTo("a".repeat(64));
        verify(tallerQrCobroRepository).findSha256ByTallerId(TALLER_ID);
        verifyNoMoreInteractions(tallerQrCobroRepository);
    }

    private void preparar(Reparacion reparacion, List<Cobro> cobros) {
        when(tenantService.currentTallerId()).thenReturn(TALLER_ID);
        when(reparacionRepository.findByIdAndTallerId(REPARACION_ID, TALLER_ID))
                .thenReturn(Optional.of(reparacion));
        when(cobroRepository
                .findByReparacionIdAndTallerIdAndAnuladoAtIsNullOrderByCreatedAtAscIdAsc(
                        REPARACION_ID, TALLER_ID))
                .thenReturn(cobros);
    }

    private Reparacion reparacion(boolean mostrarEnResumen, String alias) {
        Taller taller = Taller.builder()
                .id(TALLER_ID)
                .nombre("Taller Unitario")
                .telefono("1100000000")
                .aliasCobro(alias)
                .mostrarEnResumen(mostrarEnResumen)
                .build();
        Cliente cliente = Cliente.builder()
                .nombre("Ana")
                .apellido("Pérez")
                .telefono("1111111111")
                .taller(taller)
                .build();
        Equipo equipo = Equipo.builder()
                .marca("Samsung")
                .modelo("A54")
                .cliente(cliente)
                .taller(taller)
                .build();
        return Reparacion.builder()
                .id(REPARACION_ID)
                .numeroOrden("ORD-2026-0071")
                .codigoSeguimiento("SEGUIMIENTO71")
                .descripcionProblema("No enciende")
                .precioEstimado(new BigDecimal("100"))
                .equipo(equipo)
                .taller(taller)
                .createdAt(LocalDateTime.of(2026, 8, 23, 9, 0))
                .build();
    }
}
