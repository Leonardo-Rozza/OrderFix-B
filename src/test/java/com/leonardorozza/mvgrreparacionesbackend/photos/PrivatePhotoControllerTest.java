package com.leonardorozza.mvgrreparacionesbackend.photos;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MomentoFoto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Servlet/JSON transport tests with service doubles. Real filter-chain, tenant, storage and
 * independently committed evidence are checked by the PG and opt-in Boot/browser gates. */
class PrivatePhotoControllerTest {
    private static final String BASE="/api/reparaciones/31";
    private static final UUID ID=UUID.fromString("e45b7ec1-b8e7-419f-ae8c-4b6ed4878c77");
    private static final String KEY="b2b66d51-9452-4cd2-94d8-c0a1d2b91fea";
    private static final String REVISION="sha256:"+"a".repeat(64);
    private static final String BODY="""
            {"nombre":"foto-sintetica.png","mimeType":"image/png","bytes":99,"sha256":"%s","momento":"INGRESO",
             "requiredSetRevision":"%s","aceptacionesLegales":[],
             "atestacion":{"tipo":"AUTORIZACION_DATOS_CLIENTE","alcances":["FOTOS"],"confirmada":true}}
            """.formatted("b".repeat(64),REVISION);
    private static final Instant TIME=Instant.parse("2026-09-09T12:00:00Z");
    private PrivatePhotoService service;
    private ObjectProvider<PrivatePhotoService> provider;
    private PrivatePhotoController controller;
    private AuthenticatedUserPrincipal principal;
    private MockMvc mvc;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        service=mock(PrivatePhotoService.class);provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        controller=new PrivatePhotoController(provider);
        principal=new AuthenticatedUserPrincipal(User.builder().id(7L).username("Actor sintético").email("actor@fixture.test")
                .password("synthetic-hash").role(UserRole.ADMIN).active(true)
                .taller(Taller.builder().id(11L).activo(true).build()).build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        mvc=MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}

    @Test void unavailableCapabilityIsSanitizedJsonEvenForAnIncompatibleAccept() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        mvc.perform(get(BASE+"/requisitos-fotos").accept(MediaType.TEXT_HTML))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store")).andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.code").value("FOTOS_PRIVADAS_NO_DISPONIBLES"))
                .andExpect(jsonPath("$.message").value("No se pudo completar la operación de fotos."))
                .andExpect(jsonPath("$.details").isMap());
        verifyNoInteractions(service);
    }
    @Test void requirementsExposeOnlyConfiguredPhotoLimitsAndNoStorageCapability() throws Exception {
        when(service.requirements(principal,31)).thenReturn(new Requirements("es-AR",REVISION,List.of(),new Limits(8_000_000,List.of("image/jpeg","image/png"),100)));
        mvc.perform(get(BASE+"/requisitos-fotos"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.locale").value("es-AR")).andExpect(jsonPath("$.requiredSetRevision").value(REVISION))
                .andExpect(jsonPath("$.limites.maxBytes").value(8_000_000)).andExpect(jsonPath("$.limites.maxFotos").value(100))
                .andExpect(jsonPath("$.limites.mimeTypes[0]").value("image/jpeg")).andExpect(jsonPath("$.limites.mimeTypes[1]").value("image/png"))
                .andExpect(jsonPath("$.upload").doesNotExist()).andExpect(jsonPath("$.signature").doesNotExist());
        verify(service).requirements(principal,31);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void creationCarriesOnlyServerPrincipalAndPeerMetadataAndMapsReplayStatus(boolean reused) throws Exception {
        when(service.create(eq(principal),eq(31L),eq(KEY),any(),any())).thenReturn(new Result<>(intention("AUTORIZADA"),reused));
        mvc.perform(post(BASE+"/cargas-foto").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",KEY)
                        .header("User-Agent","Synthetic photo browser").header("X-Forwarded-For","untrusted-data")
                        .content(BODY).with(request->{request.setRemoteAddr("192.0.2.5");return request;}))
                .andExpect(status().is(reused?200:201)).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.id").value(ID.toString())).andExpect(jsonPath("$.estado").value("AUTORIZADA"))
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.url").value(BASE+"/cargas-foto/"+ID+"/contenido"))
                .andExpect(jsonPath("$.assetId").doesNotExist()).andExpect(jsonPath("$.objectKey").doesNotExist());
        var body=ArgumentCaptor.forClass(Create.class);var metadata=ArgumentCaptor.forClass(LegalRequestMetadata.class);
        verify(service).create(eq(principal),eq(31L),eq(KEY),body.capture(),metadata.capture());
        assertThat(body.getValue().nombre()).isEqualTo("foto-sintetica.png");assertThat(body.getValue().bytes()).isEqualTo(99);
        assertThat(body.getValue().requiredSetRevision()).isEqualTo(REVISION);assertThat(body.getValue().atestacion().confirmada()).isTrue();
        assertThat(metadata.getValue().ipAddress()).isEqualTo("192.0.2.5");assertThat(metadata.getValue().userAgent()).isEqualTo("Synthetic photo browser");
    }
    @Test void malformedBodyAndUuidBecomeSafeNonCacheableErrorsBeforeService() throws Exception {
        mvc.perform(post(BASE+"/cargas-foto").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"synthetic-secret"))
                .andExpect(status().isBadRequest()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.code").value("FOTO_INVALIDA"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-secret"))));
        mvc.perform(get(BASE+"/cargas-foto/not-an-id"))
                .andExpect(status().isBadRequest()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.code").value("FOTO_INVALIDA"));
        verifyNoInteractions(service);
    }
    @Test void invalidPeerMetadataFailsBeforeBusinessCall() throws Exception {
        mvc.perform(post(BASE+"/cargas-foto").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",KEY).content(BODY)
                        .with(request->{request.setRemoteAddr("not-an-ip");return request;}))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.code").value("FOTOS_PRIVADAS_NO_DISPONIBLES"));
        verifyNoInteractions(service);
    }
    @Test void uploadAuthorizesExistingIntentBeforeOpeningBytes() throws Exception {
        var request=new ObservedRequest(new byte[]{1,2,3},3);
        when(service.intention(principal,31,ID)).thenAnswer(call->{assertThat(request.opened).isFalse();return intention("AUTORIZADA");});
        when(service.upload(eq(principal),eq(31L),eq(ID),eq("image/png"),any())).thenReturn(intention("SUBIDA"));
        var result=controller.upload(principal,31,ID,request);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getBody().estado()).isEqualTo("SUBIDA");assertThat(request.opened).isTrue();assertThat(request.closed).isFalse();
        var bytes=ArgumentCaptor.forClass(byte[].class);verify(service).upload(eq(principal),eq(31L),eq(ID),eq("image/png"),bytes.capture());
        assertThat(bytes.getValue()).containsExactly(1,2,3);verify(service).intention(principal,31,ID);
    }
    @Test void foreignIntentStopsUploadBeforeReadingOrForwardingAnyBytes() throws Exception {
        var request=new ObservedRequest(new byte[]{1},1);
        when(service.intention(principal,31,ID)).thenThrow(PrivatePhotoException.missing());
        assertThatThrownBy(()->controller.upload(principal,31,ID,request)).isInstanceOf(PrivatePhotoException.class);
        assertThat(request.opened).isFalse();verify(service,never()).upload(any(),anyLong(),any(),any(),any());
    }
    @Test void declaredOversizeIsRejectedWithoutOpeningTheBody() throws Exception {
        var request=new ObservedRequest(new byte[]{1},8_000_001);
        when(service.intention(principal,31,ID)).thenReturn(intention("AUTORIZADA"));
        assertThatThrownBy(()->controller.upload(principal,31,ID,request)).isInstanceOf(PrivatePhotoException.class);
        assertThat(request.opened).isFalse();verify(service,never()).upload(any(),anyLong(),any(),any(),any());
    }
    @Test void unknownLengthUploadReadsOnlyTheLimitPlusOneForServiceRejection() throws Exception {
        var request=new ObservedRequest(new byte[8_000_020],-1);
        when(service.intention(principal,31,ID)).thenReturn(intention("AUTORIZADA"));
        when(service.upload(eq(principal),eq(31L),eq(ID),any(),any())).thenAnswer(call->{
            assertThat(((byte[])call.getArgument(4)).length).isEqualTo(8_000_001);throw PrivatePhotoException.invalid();
        });
        assertThatThrownBy(()->controller.upload(principal,31,ID,request)).isInstanceOf(PrivatePhotoException.class);
        assertThat(request.stream.available()).isEqualTo(19);assertThat(request.closed).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalizationHasNoPayloadAndMapsNewVersusReusedEvidence(boolean reused) throws Exception {
        when(service.finish(principal,31,ID)).thenReturn(new Result<>(photo(),reused));
        mvc.perform(post(BASE+"/cargas-foto/"+ID+"/finalizaciones"))
                .andExpect(status().is(reused?200:201)).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.id").value(ID.toString())).andExpect(jsonPath("$.sha256").value("b".repeat(64)))
                .andExpect(jsonPath("$.assetId").doesNotExist()).andExpect(jsonPath("$.url").doesNotExist());
        verify(service).finish(principal,31,ID);
    }
    @Test void evenOneUnexpectedFinalizationByteIsInvalidWithoutCallingService() throws Exception {
        mvc.perform(post(BASE+"/cargas-foto/"+ID+"/finalizaciones").content(" "))
                .andExpect(status().isBadRequest()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.code").value("FOTO_INVALIDA"));
        verifyNoInteractions(service);
    }
    @Test void readsReturnOnlyBinaryBytesAndDeletionReturnsAnEmpty204() throws Exception {
        when(service.content(principal,31,ID)).thenReturn(new Content("image/png",new byte[]{9,8,7}));
        mvc.perform(get(BASE+"/fotos/"+ID+"/contenido"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{9,8,7}))
                .andExpect(content().contentType("image/png")).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=photo"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mvc.perform(delete(BASE+"/fotos/"+ID)).andExpect(status().isNoContent())
                .andExpect(content().bytes(new byte[0])).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"));
        verify(service).content(principal,31,ID);verify(service).delete(principal,31,ID);
    }
    @Test void inProgressKeepsRetryAfterWithoutLeakingProviderDetails() throws Exception {
        when(service.intention(principal,31,ID)).thenThrow(PrivatePhotoException.of(HttpStatus.CONFLICT,"OPERACION_EN_PROGRESO"));
        mvc.perform(get(BASE+"/cargas-foto/"+ID).accept(MediaType.TEXT_HTML))
                .andExpect(status().isConflict()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER,"1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OPERACION_EN_PROGRESO"))
                .andExpect(jsonPath("$.message").value("No se pudo completar la operación de fotos."));
    }
    private static Intention intention(String state) {return new Intention(ID,state,TIME,new Upload("PUT",BASE+"/cargas-foto/"+ID+"/contenido"));}
    private static Photo photo() {return new Photo(ID,MomentoFoto.INGRESO,"image/png",99,"b".repeat(64),TIME);}
    private static class ObservedRequest extends MockHttpServletRequest {
        final ByteArrayInputStream stream;final long length;boolean opened,closed;
        ObservedRequest(byte[] bytes,long length) {this.stream=new ByteArrayInputStream(bytes);this.length=length;setContentType("image/png");}
        @Override public long getContentLengthLong(){return length;}
        @Override public ServletInputStream getInputStream(){opened=true;return new DelegatingServletInputStream(stream){
            @Override public void close() throws IOException {closed=true;super.close();}
        };}
    }
}
