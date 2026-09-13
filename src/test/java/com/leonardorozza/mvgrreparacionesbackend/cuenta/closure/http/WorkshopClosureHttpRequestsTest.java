package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePurpose;
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

class WorkshopClosureHttpRequestsTest {
    private static final String ID="b46c821a-a9de-4cb0-8a74-24c0c728502e";
    @Test void requestIsBoundToBothCanonicalIdentifiersAndPreservesPasswordWhitespace() {
        var value=WorkshopClosureHttpRequests.reauthentication(body(reauth(" current password ")));
        assertThat(value.passwordActual()).isEqualTo(" current password "); assertThat(value.purpose()).isEqualTo(WorkshopClosurePurpose.CERRAR);
        assertThat(value.operationId()).isEqualTo(UUID.fromString(ID)); assertThat(value.reference()).isEqualTo(value.operationId());
        assertThat(value.toString()).isEqualTo("Reauthentication[redacted]");
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","{}","true",
            "{\"passwordActual\":\"private\",\"passwordActual\":\"second\"}",
            "{\"passwordActual\":{\"nested\":{}}}","{\"passwordActual\":12}"})
    void malformedBodiesFailWithoutReflectingPayload(String content) {
        assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(body(content))).isInstanceOf(WorkshopClosureHttpException.class)
                .hasNoCause().hasMessageNotContaining("private").hasMessageNotContaining("second");
    }
    @Test void duplicateTrailingUnknownFieldsAndForeignPurposeAreRejected() {
        for(String malformed:new String[]{reauth("private")+" {}",reauth("private").replace("\"proposito\":", "\"passwordActual\":\"second\",\"proposito\":"),
                reauth("private").replace("}",",\"tallerId\":7}"),reauth("private").replace("CERRAR","EXPORTAR"),
                reauth("private").replace(ID,ID.toUpperCase()),reauth(" "),reauth("x".repeat(101))})
            assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(body(malformed))).isInstanceOf(WorkshopClosureHttpException.class).hasNoCause();
        assertThat(WorkshopClosureHttpRequests.reauthentication(body(reauth("x".repeat(100)))).passwordActual()).hasSize(100);
    }
    @Test void closureReferenceMustEqualOperationForClosingAndPhrasesAreExact() {
        String command=command("CERRAR MI TALLER");
        assertThat(WorkshopClosureHttpRequests.command(body(command)).confirmation()).isEqualTo("CERRAR MI TALLER");
        for(String phrase:new String[]{"cerrar mi taller","CERRAR MI TALLER ","RESTAURAR MI TALLER"})
            assertThatThrownBy(()->WorkshopClosureHttpRequests.command(body(command(phrase)))).isInstanceOf(WorkshopClosureHttpException.class);
        String foreign=command.replace("\"cierreReferencia\":\""+ID,"\"cierreReferencia\":\""+UUID.randomUUID());
        assertThatThrownBy(()->WorkshopClosureHttpRequests.command(body(foreign))).isInstanceOf(WorkshopClosureHttpException.class);
        var restore=body(foreign.replace("CERRAR","RESTAURAR"));
        assertThat(WorkshopClosureHttpRequests.command(restore).purpose()).isEqualTo(WorkshopClosurePurpose.RESTAURAR);
    }
    @Test void theUnknownLengthBodyIsLimitedToFourKibibytesPlusOneSentinel() {
        AtomicInteger reads=new AtomicInteger();byte[] bytes=new byte[20_000];
        var request=new MockHttpServletRequest(){
            @Override public long getContentLengthLong(){return -1;}
            @Override public ServletInputStream getInputStream(){var source=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                @Override public int read(){reads.incrementAndGet();return source.read();}
                @Override public boolean isFinished(){return source.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){}
            };}
        };
        assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(request)).isInstanceOfSatisfying(WorkshopClosureHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(413));
        assertThat(reads.get()).isEqualTo(4097);
    }
    @Test void bodyEncodingMustReallyBeUtf8() {
        var utf16=body(reauth("private"));utf16.setContent(reauth("private").getBytes(StandardCharsets.UTF_16LE));
        assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(utf16)).isInstanceOf(WorkshopClosureHttpException.class).hasNoCause();
        var malformed=body(reauth("private"));malformed.setContent(new byte[]{(byte)0xc3,0x28});
        assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(malformed)).isInstanceOf(WorkshopClosureHttpException.class).hasNoCause();
    }
    @Test void oversizedDeclaredBodyNeverOpensTheStream() {
        var request=new MockHttpServletRequest(){
            @Override public long getContentLengthLong(){return 4097;}
            @Override public ServletInputStream getInputStream(){throw new AssertionError("must not open stream");}
        };
        assertThatThrownBy(()->WorkshopClosureHttpRequests.reauthentication(request)).isInstanceOfSatisfying(WorkshopClosureHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(413));
    }
    @ParameterizedTest @ValueSource(strings={"Authorization","X-Reauth-Token","Content-Type","Content-Length","Transfer-Encoding","Content-Encoding"})
    void duplicateSensitiveHeadersAreRejected(String header) {
        var request=body(reauth("private"));request.removeHeader(header);request.addHeader(header,"one");request.addHeader(header,"two");
        assertThatThrownBy(()->WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.ISSUE)).isInstanceOf(WorkshopClosureHttpException.class);
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/problem+json","application/json; charset=ISO-8859-1","application/json; boundary=unexpected"})
    void onlyJsonUtf8IsAccepted(String type) {
        var request=body(reauth("private"));request.setContentType(type);
        assertThatThrownBy(()->WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.ISSUE)).isInstanceOfSatisfying(WorkshopClosureHttpException.class,
                failure->assertThat(failure.status.value()).isEqualTo(415));
    }
    @Test void methodsQueriesSuffixesAndEncodedAliasesCannotReachConsumers() {
        for(String path:new String[]{WorkshopClosureHttpRequests.ROOT+"/",WorkshopClosureHttpRequests.ROOT+"/%6fperaciones",WorkshopClosureHttpRequests.ROOT+"/operaciones/more"})
            assertThatThrownBy(()->WorkshopClosureHttpRequests.operation(new MockHttpServletRequest("POST",path))).isInstanceOf(WorkshopClosureHttpException.class);
        for(String method:new String[]{"HEAD","POST","DELETE"})
            assertThatThrownBy(()->WorkshopClosureHttpRequests.operation(new MockHttpServletRequest(method,WorkshopClosureHttpRequests.ROOT))).isInstanceOfSatisfying(WorkshopClosureHttpException.class,f->assertThat(f.status.value()).isEqualTo(405));
        var request=new MockHttpServletRequest("GET","/app"+WorkshopClosureHttpRequests.ROOT);request.setContextPath("/app");
        assertThat(WorkshopClosureHttpRequests.operation(request)).isEqualTo(WorkshopClosureHttpRequests.Operation.READ);
        request.setQueryString("secret=private");assertThatThrownBy(()->WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.READ)).isInstanceOf(WorkshopClosureHttpException.class).hasMessageNotContaining("private");
    }
    @Test void proofsAreSingleCanonicalBase64urlAndGetDoesNotAcceptBody() {
        var request=new MockHttpServletRequest();String proof=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        request.addHeader("X-Reauth-Token",proof);assertThat(WorkshopClosureHttpRequests.proof(request)).isEqualTo(proof);
        request.removeHeader("X-Reauth-Token");request.addHeader("X-Reauth-Token","A".repeat(42)+"B");
        assertThatThrownBy(()->WorkshopClosureHttpRequests.proof(request)).isInstanceOfSatisfying(WorkshopClosureHttpException.class,f->assertThat(f.code).isEqualTo("REAUTENTICACION_INVALIDA"));
        assertThatThrownBy(()->WorkshopClosureHttpRequests.emptyBody(body("{}"))).isInstanceOf(WorkshopClosureHttpException.class);
        assertThatThrownBy(()->WorkshopClosureHttpRequests.uuid("1-1-1-1-1")).isInstanceOf(WorkshopClosureHttpException.class);
    }
    static String reauth(String password){return "{\"passwordActual\":\""+password+"\",\"proposito\":\"CERRAR\",\"operacionId\":\""+ID+"\",\"cierreReferencia\":\""+ID+"\"}";}
    static String command(String phrase){return "{\"confirmacion\":\""+phrase+"\",\"proposito\":\"CERRAR\",\"operacionId\":\""+ID+"\",\"cierreReferencia\":\""+ID+"\"}";}
    static MockHttpServletRequest body(String content){var request=new MockHttpServletRequest("POST",WorkshopClosureHttpRequests.REAUTH);request.setContentType("application/json");request.setContent(content.getBytes(StandardCharsets.UTF_8));return request;}
}
