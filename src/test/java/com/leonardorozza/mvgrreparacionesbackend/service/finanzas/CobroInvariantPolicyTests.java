package com.leonardorozza.mvgrreparacionesbackend.service.finanzas;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CobroInvariantPolicyTests {

    private final CobroInvariantPolicy policy = new CobroInvariantPolicy();

    @Test
    void estadoCuentaNuncaExponeSaldoNegativoYSeparaElExcedente() {
        EstadoCuentaOrden estado = EstadoCuentaOrden.de(bd("50000"), bd("138323"));

        assertThat(estado.total()).isEqualByComparingTo("50000");
        assertThat(estado.cobrado()).isEqualByComparingTo("138323");
        assertThat(estado.saldo()).isEqualByComparingTo("0");
        assertThat(estado.excedente()).isEqualByComparingTo("88323");
        assertThat(estado.requiereRevision()).isTrue();
        assertThat(estado.pagado()).isTrue();
    }

    @Test
    void totalCeroNoSeConsideraPagado() {
        assertThat(EstadoCuentaOrden.de(BigDecimal.ZERO, BigDecimal.ZERO).pagado()).isFalse();
    }

    @Test
    void rechazaCobrosSuperioresAlPendienteConDetallesEstables() {
        EstadoCuentaOrden estado = EstadoCuentaOrden.de(bd("50000"), bd("20000"));

        assertThatThrownBy(() -> policy.validarNuevoCobro(7L, estado, bd("30001")))
                .isInstanceOfSatisfying(ConflictException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("COBRO_SUPERA_SALDO");
                    assertThat(error.getDetails()).containsEntry("reparacionId", 7L)
                            .containsEntry("pendiente", bd("30000"));
                });
        assertThatCode(() -> policy.validarNuevoCobro(7L, estado, bd("30000")))
                .doesNotThrowAnyException();
    }

    @Test
    void unLegacySoloPermiteTotalesQueNoAumentenElExcedente() {
        EstadoCuentaOrden legacy = EstadoCuentaOrden.de(bd("50000"), bd("60000"));

        assertThatCode(() -> policy.validarTotalProyectado(9L, legacy, bd("50000")))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validarTotalProyectado(9L, legacy, bd("55000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validarTotalProyectado(9L, legacy, bd("49999")))
                .isInstanceOfSatisfying(ConflictException.class, error ->
                        assertThat(error.getCode()).isEqualTo("TOTAL_MENOR_QUE_COBRADO"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
