package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Lógica de vencimiento del presupuesto (pura, sin Spring). */
class PresupuestoVencidoTest {

    @Test
    void pendienteExpiradoEstaVencido() {
        Presupuesto p = Presupuesto.builder()
                .estado(EstadoPresupuesto.PENDIENTE)
                .validoHasta(LocalDateTime.now().minusDays(1))
                .build();
        assertThat(p.isVencido()).isTrue();
        assertThat(p.getEstadoEfectivo()).isEqualTo(EstadoPresupuesto.VENCIDO);
    }

    @Test
    void pendienteVigenteNoEstaVencido() {
        Presupuesto p = Presupuesto.builder()
                .estado(EstadoPresupuesto.PENDIENTE)
                .validoHasta(LocalDateTime.now().plusDays(3))
                .build();
        assertThat(p.isVencido()).isFalse();
        assertThat(p.getEstadoEfectivo()).isEqualTo(EstadoPresupuesto.PENDIENTE);
    }

    @Test
    void aprobadoNuncaEstaVencido() {
        Presupuesto p = Presupuesto.builder()
                .estado(EstadoPresupuesto.APROBADO)
                .validoHasta(LocalDateTime.now().minusDays(10))
                .build();
        assertThat(p.isVencido()).isFalse();
        assertThat(p.getEstadoEfectivo()).isEqualTo(EstadoPresupuesto.APROBADO);
    }
}
