package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ExportHttpRequestsTest {
    @Test void exactBodyKeepsThePasswordWithoutTrimmingAndRedactsIt() {
        var request=body("{\"passwordActual\":\" current password \",\"proposito\":\"EXPORTAR\"}");
        var value=ExportHttpRequests.reauthentication(request);
        assertThat(value.passwordActual()).isEqualTo(" current password ");
        assertThat(value.proposito()).isEqualTo(ExportReauthenticationPurpose.EXPORTAR);
        assertThat(value.toString()).doesNotContain("current password");
    }
    @ParameterizedTest @ValueSource(strings={
        "{}","null","[]","true", "{\"passwordActual\":12,\"proposito\":\"EXPORTAR\"}",
        "{\"passwordActual\":\" \",\"proposito\":\"EXPORTAR\"}",
        "{\"passwordActual\":\"private-password\",\"proposito\":\"CERRAR\"}",
        "{\"passwordActual\":\"private-password\",\"proposito\":\"EXPORTAR\",\"tallerId\":9}",
        "{\"passwordActual\":\"private-password\",\"passwordActual\":\"second\",\"proposito\":\"EXPORTAR\"}",
        "{\"passwordActual\":\"private-password\",\"proposito\":\"EXPORTAR\"} {}",
        "{\"passwordActual\":[[[]]],\"proposito\":\"EXPORTAR\"}"
    }) void invalidShapesAreSanitizedBeforeCallingThePasswordService(String json) {
        assertThatThrownBy(()->ExportHttpRequests.reauthentication(body(json))).isInstanceOf(ExportHttpException.class)
                .hasNoCause().hasMessageNotContaining("private-password").hasMessageNotContaining("second");
    }
    @Test void passwordLengthUsesTheExistingOneHundredCharacterContract() {
        assertThat(ExportHttpRequests.reauthentication(body("{\"passwordActual\":\""+"a".repeat(100)+"\",\"proposito\":\"DESCARGAR_EXPORTACION\"}")).passwordActual()).hasSize(100);
        assertThatThrownBy(()->ExportHttpRequests.reauthentication(body("{\"passwordActual\":\""+"a".repeat(101)+"\",\"proposito\":\"EXPORTAR\"}"))).isInstanceOf(ExportHttpException.class);
    }
    @Test void unknownLengthBodyConsumesAtMostFourKibibytesAndOneSentinel() {
        AtomicInteger read=new AtomicInteger(); byte[] content=new byte[20_000];
        var request=new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(content);
                return new ServletInputStream() {
                    @Override public int read() { read.incrementAndGet();return input.read(); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { }
                };
            }
        };
        assertThatThrownBy(()->ExportHttpRequests.reauthentication(request)).isInstanceOfSatisfying(ExportHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(413));
        assertThat(read.get()).isEqualTo(4097);
    }
    @Test void oversizedDeclaredContentIsRejectedWithoutOpeningTheStream() {
        var request=new MockHttpServletRequest() {
            @Override public long getContentLengthLong(){return 5000;}
            @Override public ServletInputStream getInputStream(){throw new AssertionError("must not open stream");}
        };
        assertThatThrownBy(()->ExportHttpRequests.reauthentication(request)).isInstanceOfSatisfying(ExportHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(413));
    }
    @Test void credentialsAndIdempotencyRequireSingleCanonicalHeaders() {
        var request=new MockHttpServletRequest(); String proof=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        request.addHeader("Authorization","Bearer signed.jwt.token"); request.addHeader("X-Reauth-Token",proof);
        UUID id=UUID.randomUUID();request.addHeader("Idempotency-Key",id.toString());
        assertThat(ExportHttpRequests.accessToken(request)).isEqualTo("signed.jwt.token");
        assertThat(ExportHttpRequests.proof(request)).isEqualTo(proof);assertThat(ExportHttpRequests.requestKey(request)).isEqualTo(id);
        request.addHeader("X-Reauth-Token",proof);
        var duplicated=request;
        assertThatThrownBy(()->ExportHttpRequests.proof(duplicated)).isInstanceOf(ExportHttpException.class);
        request=new MockHttpServletRequest();request.addHeader("X-Reauth-Token","A".repeat(42)+"B");
        var noncanonical=request;
        assertThatThrownBy(()->ExportHttpRequests.proof(noncanonical)).isInstanceOfSatisfying(ExportHttpException.class,
                failure->assertThat(failure.code).isEqualTo("REAUTENTICACION_INVALIDA"));
        assertThatThrownBy(()->ExportHttpRequests.canonicalUuid("1-1-1-1-1")).isInstanceOf(ExportHttpException.class);
    }
    @Test void contextPathIsSupportedButQueriesHeadAndEncodedAliasesDoNotReachAConsumer() {
        var request=new MockHttpServletRequest("GET","/app/api/exportaciones/actual");request.setContextPath("/app");
        assertThat(ExportHttpRequests.operation(request)).isEqualTo(ExportHttpRequests.Operation.READ);
        request.setQueryString("reauthToken=private");
        assertThatThrownBy(()->ExportHttpRequests.require(request,ExportHttpRequests.Operation.READ)).isInstanceOf(ExportHttpException.class).hasMessageNotContaining("private");
        assertThatThrownBy(()->ExportHttpRequests.operation(new MockHttpServletRequest("HEAD","/api/export/excel"))).isInstanceOfSatisfying(ExportHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(405));
        assertThatThrownBy(()->ExportHttpRequests.require(new MockHttpServletRequest("POST","/api/export/%65xcel"),ExportHttpRequests.Operation.DOWNLOAD)).isInstanceOf(ExportHttpException.class);
    }
    @Test void proofHeadersNeverMakeAPostBodyAcceptable() {
        var request=body("{}");
        assertThatThrownBy(()->ExportHttpRequests.emptyBody(request)).isInstanceOf(ExportHttpException.class);
        ExportHttpRequests.emptyBody(new MockHttpServletRequest());
    }
    private static MockHttpServletRequest body(String json) {
        var request=new MockHttpServletRequest("POST",ExportHttpRequests.REAUTH);request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));return request;
    }
}
