package com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalAccessExitRequestTest {
    @Test void preservesPasswordWhitespaceAndRedactsDiagnosticRepresentation() {
        var request = read("{\"passwordActual\":\" secret123 \",\"confirmado\":true}");
        assertThat(request.passwordActual()).isEqualTo(" secret123 ");
        assertThat(request.confirmado()).isTrue();
        assertThat(request.toString()).isEqualTo("PersonalAccessExitRequest[redacted]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{}",
            "{\"passwordActual\":\"secret123\"}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":false}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":\"true\"}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":1}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":null}",
            "{\"passwordActual\":null,\"confirmado\":true}",
            "{\"passwordActual\":12345678,\"confirmado\":true}",
            "{\"passwordActual\":\"   \",\"confirmado\":true}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":true,\"userId\":7}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":true,\"tallerId\":7}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":true,\"confirmado\":true}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":true} {}",
            "{\"passwordActual\":\"secret123\",\"confirmado\":true"
    })
    void rejectsMalformedOrNonExactBodiesWithoutRetainingTheirContents(String body) {
        assertThatThrownBy(() -> read(body)).isInstanceOfSatisfying(BadRequestException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo("BAJA_ACCESO_INVALIDA");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("secret123");
        });
    }

    @Test void bodyAndPasswordLimitsAreBounded() {
        assertThat(read("{\"passwordActual\":\"" + "a".repeat(100) + "\",\"confirmado\":true}").passwordActual()).hasSize(100);
        assertThatThrownBy(() -> read("{\"passwordActual\":\"" + "a".repeat(101) + "\",\"confirmado\":true}"))
                .isInstanceOf(BadRequestException.class);
        var oversized = new ByteArrayInputStream(" ".repeat(5000).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PersonalAccessExitRequest.read(oversized)).isInstanceOf(BadRequestException.class);
        assertThat(oversized.available()).isEqualTo(903);
    }

    private PersonalAccessExitRequest read(String body) {
        return PersonalAccessExitRequest.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
