package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.mercadopago.MercadoPagoProperties;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SuscripcionServiceTest {
    private static final long TALLER_ID = 42L;
    private static final Map<String, Boolean> FUNCIONES = Map.of(
            "inventario", true, "cobros", true, "empleadosMultiples", true);
    private final SuscripcionRepository suscripciones = mock(SuscripcionRepository.class);
    private final TenantService tenant = mock(TenantService.class);
    private final PlanFeatureService funciones = mock(PlanFeatureService.class);
    private final MercadoPagoProperties mercadoPago = new MercadoPagoProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"),
            ZoneId.of("America/Argentina/Buenos_Aires"));
    private final SuscripcionService servicio = new SuscripcionService(
            suscripciones, tenant, funciones, clock, mercadoPago);
    private Suscripcion suscripcion;

    @BeforeEach
    void prepararSuscripcionDelTaller() {
        suscripcion = Suscripcion.builder()
                .plan(PlanType.FREE)
                .estado(EstadoSuscripcion.ACTIVA)
                .fechaInicio(LocalDate.of(2026, 8, 1))
                .fechaFinTrial(LocalDate.of(2026, 8, 15))
                .consumoMes("2026-09")
                .reparacionesMes(9)
                .build();
        when(tenant.currentTallerId()).thenReturn(TALLER_ID);
        when(suscripciones.findByTallerId(TALLER_ID)).thenReturn(Optional.of(suscripcion));
        when(funciones.capacidades()).thenReturn(FUNCIONES);
        ReflectionTestUtils.setField(servicio, "freeMaxReparacionesMes", 25);
    }

    @ParameterizedTest
    @CsvSource({"false, false, false", "false, true, false", "true, false, false", "true, true, true"})
    void ofertaUsaPrecioConfiguradoYRequiereAmbasHabilitaciones(boolean integrada, boolean altas, boolean disponible) {
        mercadoPago.setEnabled(integrada);
        mercadoPago.setCheckoutEnabled(altas);
        mercadoPago.setAmount(new BigDecimal("32145.67"));
        mercadoPago.setCurrency(" ars ");

        var respuesta = servicio.miSuscripcion();

        assertThat(respuesta.ofertaPro()).isNotNull();
        assertThat(respuesta.ofertaPro().precioMensual()).isEqualByComparingTo("32145.67");
        assertThat(respuesta.ofertaPro().moneda()).isEqualTo("ARS");
        assertThat(respuesta.ofertaPro().contratacionDisponible()).isEqualTo(disponible);
        // La lectura no normaliza ni modifica la configuración del proveedor.
        assertThat(mercadoPago.getCurrency()).isEqualTo(" ars ");
        verificarLecturaDelTaller();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01"})
    void importeAusenteOCeroONegativoOmiteOfertaSinRomperElPlan(String importe) {
        mercadoPago.setAmount(importe == null ? null : new BigDecimal(importe));

        var respuesta = servicio.miSuscripcion();

        assertThat(respuesta.ofertaPro()).isNull();
        assertThat(respuesta.plan()).isEqualTo(PlanType.FREE);
        assertThat(respuesta.reparacionesEsteMes()).isEqualTo(9);
        assertThat(respuesta.funciones()).isEqualTo(FUNCIONES);
        verificarLecturaDelTaller();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "AR", "ARS1", "AR S", "$AR"})
    void monedaInvalidaOmiteOfertaConMercadoPagoApagado(String moneda) {
        mercadoPago.setCurrency(moneda);

        var respuesta = servicio.miSuscripcion();

        assertThat(respuesta.ofertaPro()).isNull();
        assertThat(respuesta.limiteReparacionesMes()).isEqualTo(25);
        verificarLecturaDelTaller();
    }

    @Test
    void trialConservaFuncionesYConsumoAunqueLaContratacionEsteApagada() {
        suscripcion.setEstado(EstadoSuscripcion.TRIAL);
        suscripcion.setFechaFinTrial(LocalDate.of(2026, 9, 25));
        when(funciones.esPro(TALLER_ID)).thenReturn(true);

        var respuesta = servicio.miSuscripcion();

        assertThat(respuesta.plan()).isEqualTo(PlanType.FREE);
        assertThat(respuesta.estado()).isEqualTo(EstadoSuscripcion.TRIAL);
        assertThat(respuesta.fechaInicio()).isEqualTo(suscripcion.getFechaInicio());
        assertThat(respuesta.fechaFinTrial()).isEqualTo(suscripcion.getFechaFinTrial());
        assertThat(respuesta.reparacionesEsteMes()).isEqualTo(9);
        assertThat(respuesta.limiteReparacionesMes()).isNull();
        assertThat(respuesta.funciones()).isEqualTo(FUNCIONES);
        assertThat(respuesta.ofertaPro().contratacionDisponible()).isFalse();
        verificarLecturaDelTaller();
    }

    @Test
    void ofertaOperativaNoCambiaPlanProActivoNiElReinicioDeConsumoMensual() {
        mercadoPago.setEnabled(true);
        mercadoPago.setCheckoutEnabled(true);
        suscripcion.setPlan(PlanType.PRO);
        suscripcion.setConsumoMes("2026-08");
        suscripcion.setProximoCobro(LocalDate.of(2026, 9, 20));
        when(funciones.esPro(TALLER_ID)).thenReturn(true);

        var respuesta = servicio.miSuscripcion();

        assertThat(respuesta.plan()).isEqualTo(PlanType.PRO);
        assertThat(respuesta.estado()).isEqualTo(EstadoSuscripcion.ACTIVA);
        assertThat(respuesta.proximoCobro()).isEqualTo(suscripcion.getProximoCobro());
        assertThat(respuesta.reparacionesEsteMes()).isZero();
        assertThat(respuesta.limiteReparacionesMes()).isNull();
        // Indica oferta operativa, no autorización para crear una segunda suscripción.
        assertThat(respuesta.ofertaPro().contratacionDisponible()).isTrue();
        assertThat(suscripcion.getReparacionesMes()).isEqualTo(9);
        assertThat(suscripcion.getConsumoMes()).isEqualTo("2026-08");
        verificarLecturaDelTaller();
    }

    private void verificarLecturaDelTaller() {
        verify(suscripciones).findByTallerId(TALLER_ID);
        verifyNoMoreInteractions(suscripciones);
    }
}
