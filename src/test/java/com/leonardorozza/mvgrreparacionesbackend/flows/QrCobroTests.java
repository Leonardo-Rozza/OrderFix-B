package com.leonardorozza.mvgrreparacionesbackend.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.service.imagen.QrCobroNormalizador;
import com.leonardorozza.mvgrreparacionesbackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QrCobroTests extends IntegrationTestBase {

    private static final String URL = "/api/taller/datos-cobro/qr";
    private static final byte[] FIRMA_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    @Test
    void aceptaPngYJpegPorContenidoYDevuelvePngVersionadoSinMetadata() throws Exception {
        String token = registrar("Taller QR formatos", "qr-formatos@test.com");
        activarPro(token);
        authPut("/api/taller/datos-cobro", token, json(Map.of(
                "alias", "cobros-original.mp",
                "titular", "Titular Original",
                "mostrarEnResumen", true)))
                .andExpect(status().isOk());

        byte[] pngConMarca = concatenar(
                imagen("PNG", 96, 96, false),
                "METADATA_QUE_DEBE_DESAPARECER".getBytes(StandardCharsets.US_ASCII));
        JsonNode respuestaPng = node(putQr(token, archivo(
                "qr-disfrazado.svg", "application/pdf", pngConMarca))
                .andExpect(status().isOk()));
        String shaPng = respuestaPng.get("qrVersion").asText();
        assertThat(respuestaPng.get("qrDisponible").asBoolean()).isTrue();
        assertThat(shaPng).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(respuestaPng.get("alias").asText()).isEqualTo("cobros-original.mp");

        var getPng = authGet(URL, token).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(getPng.getContentType()).isEqualTo("image/png");
        assertThat(getPng.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + shaPng + "\"");
        assertThat(getPng.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(getPng.getContentAsByteArray()).startsWith(FIRMA_PNG);
        assertThat(sha256(getPng.getContentAsByteArray())).isEqualTo(shaPng);
        assertThat(new String(getPng.getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .doesNotContain("METADATA_QUE_DEBE_DESAPARECER");

        byte[] jpeg = imagen("JPEG", 120, 80, false);
        JsonNode respuestaJpeg = node(putQr(token, archivo(
                "qr-que-parece-texto.txt", "text/plain", jpeg))
                .andExpect(status().isOk()));
        String shaJpeg = respuestaJpeg.get("qrVersion").asText();
        assertThat(shaJpeg).hasSize(64).isNotEqualTo(shaPng);
        assertThat(respuestaJpeg.get("titular").asText()).isEqualTo("Titular Original");

        var getJpegNormalizado = authGet(URL, token)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(getJpegNormalizado.getContentAsByteArray()).startsWith(FIRMA_PNG);
        assertThat(getJpegNormalizado.getHeader(HttpHeaders.ETAG))
                .isEqualTo("\"" + shaJpeg + "\"");
        assertThat(sha256(getJpegNormalizado.getContentAsByteArray())).isEqualTo(shaJpeg);

        JsonNode metadataActualizada = node(authPut(
                "/api/taller/datos-cobro", token, json(Map.of(
                        "alias", "cobros-nuevo.mp",
                        "mostrarEnResumen", false)))
                .andExpect(status().isOk()));
        assertThat(metadataActualizada.get("alias").asText()).isEqualTo("cobros-nuevo.mp");
        assertThat(metadataActualizada.get("titular").isNull()).isTrue();
        assertThat(metadataActualizada.get("qrVersion").asText()).isEqualTo(shaJpeg);
        assertThat(authGet(URL, token).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo("\"" + shaJpeg + "\"");

        JsonNode metadata = node(authGet("/api/taller/datos-cobro", token)
                .andExpect(status().isOk()));
        assertThat(metadata.get("qrDisponible").asBoolean()).isTrue();
        assertThat(metadata.get("qrVersion").asText()).isEqualTo(shaJpeg);
        assertThat(metadata.get("alias").asText()).isEqualTo("cobros-nuevo.mp");
    }

    @Test
    void rechazaVacioSvgGifBmpYCorruptoSinPisarElQrVigente() throws Exception {
        String token = registrar("Taller QR inválidos", "qr-invalidos@test.com");
        activarPro(token);
        putQr(token, archivo("vigente.png", "image/png", imagen("PNG", 80, 80, false)))
                .andExpect(status().isOk());
        var vigente = authGet(URL, token).andExpect(status().isOk()).andReturn().getResponse();
        byte[] contenidoVigente = vigente.getContentAsByteArray();
        String etagVigente = vigente.getHeader(HttpHeaders.ETAG);

        assertQrInvalido(putQr(token, archivo("vacio.png", "image/png", new byte[0])));
        assertQrInvalido(putQr(token, archivo(
                "spoof.png", "image/png", "<svg><rect/></svg>".getBytes(StandardCharsets.UTF_8))));
        assertQrInvalido(putQr(token, archivo(
                "animado.png", "image/png", imagen("GIF", 32, 32, false))));
        assertQrInvalido(putQr(token, archivo(
                "mapa.jpg", "image/jpeg", imagen("BMP", 32, 32, false))));
        assertQrInvalido(putQr(token, archivo(
                "corrupto.png", "image/png", new byte[]{
                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3})));

        var despues = authGet(URL, token).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(despues.getContentAsByteArray()).isEqualTo(contenidoVigente);
        assertThat(despues.getHeader(HttpHeaders.ETAG)).isEqualTo(etagVigente);
    }

    @Test
    void rechazaExcesosDeEntradaDimensionesPixelesYSalidaSinPisarElQr() throws Exception {
        String token = registrar("Taller QR límites", "qr-excesos@test.com");
        activarPro(token);
        putQr(token, archivo("vigente.png", "image/png", imagen("PNG", 64, 64, false)))
                .andExpect(status().isOk());
        var vigente = authGet(URL, token).andExpect(status().isOk()).andReturn().getResponse();
        byte[] contenidoVigente = vigente.getContentAsByteArray();
        String etagVigente = vigente.getHeader(HttpHeaders.ETAG);

        byte[] demasiadoGrande = new byte[QrCobroNormalizador.MAX_BYTES + 1];
        assertArchivoGrande(putQr(token, archivo("grande.png", "image/png", demasiadoGrande)));
        assertQrInvalido(putQr(token, archivo(
                "eje.png", "image/png", imagen("PNG", 1_025, 1, false))));
        assertQrInvalido(putQr(token, archivo(
                "pixeles.png", "image/png", imagen("PNG", 1_001, 1_000, false))));

        byte[] jpegRuido = imagen("JPEG", 1_000, 1_000, true);
        byte[] pngRuido = imagen("PNG", 1_000, 1_000, true);
        assertThat(jpegRuido.length).isLessThanOrEqualTo(QrCobroNormalizador.MAX_BYTES);
        assertThat(pngRuido.length).isGreaterThan(QrCobroNormalizador.MAX_BYTES);
        assertArchivoGrande(putQr(token, archivo(
                "salida-grande.jpg", "image/jpeg", jpegRuido)));

        var despues = authGet(URL, token).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(despues.getContentAsByteArray()).isEqualTo(contenidoVigente);
        assertThat(despues.getHeader(HttpHeaders.ETAG)).isEqualTo(etagVigente);
    }

    @Test
    void aceptaBordesInclusivosDeUnMibEjeYPixeles() throws Exception {
        String token = registrar("Taller QR bordes", "qr-bordes@test.com");
        activarPro(token);

        byte[] pngPequeno = imagen("PNG", 32, 32, false);
        byte[] entradaDeUnMib = Arrays.copyOf(pngPequeno, QrCobroNormalizador.MAX_BYTES);
        putQr(token, archivo("un-mib.png", "application/octet-stream", entradaDeUnMib))
                .andExpect(status().isOk());

        putQr(token, archivo(
                "eje-1024.png", "image/png", imagen("PNG", 1_024, 976, false)))
                .andExpect(status().isOk());
        JsonNode pixelExacto = node(putQr(token, archivo(
                "un-megapixel.png", "image/png", imagen("PNG", 1_000, 1_000, false)))
                .andExpect(status().isOk()));
        assertThat(pixelExacto.get("qrVersion").asText()).hasSize(64);
    }

    @Test
    void deleteEsIdempotenteYGetFaltanteExponeCodigo() throws Exception {
        String token = registrar("Taller QR delete", "qr-delete@test.com");
        activarPro(token);

        assertSinQr(node(authDelete(URL, token).andExpect(status().isOk())));
        assertSinQr(node(authDelete(URL, token).andExpect(status().isOk())));

        JsonNode cargado = node(putQr(token, archivo(
                "qr.png", "image/png", imagen("PNG", 48, 48, false)))
                .andExpect(status().isOk()));
        assertThat(cargado.get("qrDisponible").asBoolean()).isTrue();

        assertSinQr(node(authDelete(URL, token).andExpect(status().isOk())));
        assertSinQr(node(authDelete(URL, token).andExpect(status().isOk())));
        JsonNode error = node(authGet(URL, token).andExpect(status().isNotFound()));
        assertThat(error.get("code").asText()).isEqualTo("QR_COBRO_NO_CONFIGURADO");
    }

    @Test
    void userPuedeLeerPeroSoloAdminReemplazaOElimina() throws Exception {
        String admin = registrar("Taller QR roles", "qr-roles-admin@test.com");
        activarPro(admin);
        putQr(admin, archivo("admin.png", "image/png", imagen("PNG", 64, 64, false)))
                .andExpect(status().isOk());
        authPost("/api/usuarios", admin, json(Map.of(
                "username", "Empleado QR",
                "email", "qr-roles-user@test.com",
                "password", "secret123")))
                .andExpect(status().isCreated());
        String user = login("qr-roles-user@test.com", "secret123");

        authGet(URL, user).andExpect(status().isOk());
        putQr(user, archivo("user.png", "image/png", imagen("PNG", 32, 32, false)))
                .andExpect(status().isForbidden());
        authDelete(URL, user).andExpect(status().isForbidden());
        authGet(URL, admin).andExpect(status().isOk());
    }

    @Test
    void tenantSinQrNoPuedeLeerElByteaDeOtroTaller() throws Exception {
        String a = registrar("Taller QR tenant A", "qr-tenant-a@test.com");
        String b = registrar("Taller QR tenant B", "qr-tenant-b@test.com");
        activarPro(a);
        activarPro(b);
        putQr(a, archivo("a.png", "image/png", imagen("PNG", 72, 72, false)))
                .andExpect(status().isOk());

        JsonNode errorB = node(authGet(URL, b).andExpect(status().isNotFound()));
        assertThat(errorB.get("code").asText()).isEqualTo("QR_COBRO_NO_CONFIGURADO");
        JsonNode metadataB = node(authGet("/api/taller/datos-cobro", b)
                .andExpect(status().isOk()));
        assertThat(metadataB.get("qrDisponible").asBoolean()).isFalse();
        assertThat(metadataB.get("qrVersion").isNull()).isTrue();

        authGet(URL, a).andExpect(status().isOk());
    }

    @Test
    void todosLosEndpointsQrRequierenLaFeatureCobros() throws Exception {
        String token = registrar("Taller QR free", "qr-free@test.com");
        configurarSuscripcion(token, PlanType.FREE, EstadoSuscripcion.ACTIVA);

        authGet(URL, token).andExpect(status().is(402));
        putQr(token, archivo("free.png", "image/png", imagen("PNG", 32, 32, false)))
                .andExpect(status().is(402));
        authDelete(URL, token).andExpect(status().is(402));
    }

    @Test
    void multipartSinFileEsQrInvalidoYJsonNoEsUnMultipartSoportado() throws Exception {
        String token = registrar("Taller QR multipart", "qr-multipart@test.com");
        activarPro(token);

        JsonNode sinParte = node(mvc.perform(multipart(HttpMethod.PUT, URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()));
        assertThat(sinParte.get("code").asText()).isEqualTo("QR_COBRO_INVALIDO");

        JsonNode parteIncorrecta = node(mvc.perform(multipart(HttpMethod.PUT, URL)
                        .file(new MockMultipartFile(
                                "otro", "qr.png", "image/png", imagen("PNG", 16, 16, false)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()));
        assertThat(parteIncorrecta.get("code").asText()).isEqualTo("QR_COBRO_INVALIDO");

        mvc.perform(put(URL)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private ResultActions putQr(String token, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, URL)
                .file(file)
                .header("Authorization", "Bearer " + token));
    }

    private MockMultipartFile archivo(String nombre, String contentType, byte[] contenido) {
        return new MockMultipartFile("file", nombre, contentType, contenido);
    }

    private void assertQrInvalido(ResultActions result) throws Exception {
        JsonNode error = node(result.andExpect(status().isBadRequest()));
        assertThat(error.get("code").asText()).isEqualTo("QR_COBRO_INVALIDO");
    }

    private void assertArchivoGrande(ResultActions result) throws Exception {
        JsonNode error = node(result.andExpect(status().isPayloadTooLarge()));
        assertThat(error.get("code").asText()).isEqualTo("ARCHIVO_DEMASIADO_GRAN");
    }

    private void assertSinQr(JsonNode response) {
        assertThat(response.get("qrDisponible").asBoolean()).isFalse();
        assertThat(response.get("qrVersion").isNull()).isTrue();
    }

    private byte[] imagen(String formato, int ancho, int alto, boolean ruido) throws Exception {
        BufferedImage image = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42L);
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int rgb = ruido
                        ? random.nextInt(0x1000000)
                        : ((x / 8 + y / 8) % 2 == 0 ? 0xFFFFFF : 0x111111);
                image.setRGB(x, y, rgb);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, formato, output)).isTrue();
            return output.toByteArray();
        }
    }

    private byte[] concatenar(byte[] a, byte[] b) {
        byte[] resultado = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, resultado, a.length, b.length);
        return resultado;
    }

    private String sha256(byte[] contenido) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(contenido));
    }
}
