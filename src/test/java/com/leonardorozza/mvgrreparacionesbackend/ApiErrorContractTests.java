package com.leonardorozza.mvgrreparacionesbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ArchivoDemasiadoGrandeException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ConflictException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorContractTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void conservaElContratoAnteriorCuandoLaExcepcionNoTieneCodigo() throws Exception {
        MockHttpServletRequest request = request("/api/clientes");

        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new BadRequestException("Ya existe un cliente con ese teléfono."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getError()).isEqualTo("Solicitud inválida");
        assertThat(body.getMessage()).isEqualTo("Ya existe un cliente con ese teléfono.");
        assertThat(body.getPath()).isEqualTo("/api/clientes");
        assertThat(body.getCode()).isNull();
        assertThat(body.getDetails()).isNull();

        String json = objectMapper.writeValueAsString(body);
        assertThat(json).doesNotContain("\"code\"").doesNotContain("\"details\"");
    }

    @Test
    void exponeCodigoYDetallesEnConflictosDeDominio() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reparacionId", 10L);
        details.put("total", new BigDecimal("50000"));
        details.put("cobrado", new BigDecimal("20000"));
        details.put("monto", new BigDecimal("40000"));
        details.put("pendiente", new BigDecimal("30000"));

        ConflictException exception = new ConflictException(
                " COBRO_SUPERA_SALDO ",
                "El monto supera el pendiente de cobro.",
                details);
        details.put("pendiente", BigDecimal.ZERO);

        ResponseEntity<ApiError> response = handler.handleConflict(
                exception, request("/api/reparaciones/10/cobros"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo("COBRO_SUPERA_SALDO");
        assertThat(body.getDetails()).containsEntry("reparacionId", 10L)
                .containsEntry("pendiente", new BigDecimal("30000"));
        assertThatThrownBy(() -> exception.getDetails().put("otro", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void badRequestTipadoUsaElMismoContratoAditivo() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new BadRequestException(
                        "QR_COBRO_INVALIDO",
                        "El archivo no es una imagen PNG o JPEG válida.",
                        Map.of("formatosPermitidos", "PNG,JPEG")),
                request("/api/taller/datos-cobro/qr"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("QR_COBRO_INVALIDO");
        assertThat(response.getBody().getDetails())
                .containsExactlyEntriesOf(Map.of("formatosPermitidos", "PNG,JPEG"));
    }

    @Test
    void qrNoConfiguradoExponeCodigoEstableEn404() {
        ResponseEntity<ApiError> response = handler.handleNotFound(
                new ResourceNotFoundException(
                        "QR_COBRO_NO_CONFIGURADO",
                        "El taller no tiene un QR de cobro configurado."),
                request("/api/taller/datos-cobro/qr"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("QR_COBRO_NO_CONFIGURADO");
    }

    @Test
    void rechazosDeTamanoDeServicioYMultipartCompartenElMismo413() {
        MockHttpServletRequest request = request("/api/taller/datos-cobro/qr");

        ResponseEntity<ApiError> normalizado = handler.handleArchivoDemasiadoGrande(
                new ArchivoDemasiadoGrandeException(
                        "La imagen QR normalizada supera el máximo permitido de 1 MiB."),
                request);
        ResponseEntity<ApiError> multipart = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(1_048_576L), request);

        assertArchivoDemasiadoGrande(normalizado);
        assertArchivoDemasiadoGrande(multipart);
    }

    private void assertArchivoDemasiadoGrande(ResponseEntity<ApiError> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("ARCHIVO_DEMASIADO_GRAN");
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("POST", path);
    }
}
