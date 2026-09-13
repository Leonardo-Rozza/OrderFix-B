package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ReauthenticationGrant;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkshopClosureHttpControllerTest {
    private static final UUID ID=UUID.fromString("b46c821a-a9de-4cb0-8a74-24c0c728502e");
    private static final Instant NOW=Instant.parse("2026-09-12T12:00:00Z");
    private static final String ACCESS="fixture.jwt.token",PROOF=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final WorkshopClosureStatusService status=mock(WorkshopClosureStatusService.class);
    private final WorkshopClosureReauthenticationService reauth=mock(WorkshopClosureReauthenticationService.class);
    private final WorkshopClosureCommandService commands=mock(WorkshopClosureCommandService.class);
    private final WorkshopClosureHttpController controller=new WorkshopClosureHttpController(status,reauth,commands);
    private MockMvc mvc;
    @BeforeEach void setup(){WorkshopClosureHttpGuardFilterTest.authenticate(11,UserRole.ADMIN,true);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WorkshopClosureHttpExceptionHandler()).addFilters(new WorkshopClosureHttpGuardFilter()).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void openResponseHasExplicitNullFieldsAndNoInternalDeletionOrGenerationFields()throws Exception{
        when(status.read(ACCESS)).thenReturn(new WorkshopClosureStatusService.Status("ABIERTO","Taller propio",NOW,null,null,null,true,false,null));
        String json=mvc.perform(get(WorkshopClosureHttpRequests.ROOT).header("Authorization","Bearer "+ACCESS))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.estado").value("ABIERTO")).andExpect(jsonPath("$.puedeSolicitar").value(true)).andReturn().getResponse().getContentAsString();
        var fields=new ObjectMapper().readTree(json);assertThat(fields.size()).isEqualTo(9);
        for(String nullable:List.of("referencia","confirmadoEn","reversibleHasta","ultimaOperacion")){assertThat(fields.has(nullable)).isTrue();assertThat(fields.get(nullable).isNull()).isTrue();}
        assertThat(json).doesNotContain("eliminacion","deletion","generation","session","userId");
    }
    @Test void reauthenticationReturnsOnlyTheOpaqueGrantAndExpiryWithExactIdentityBindings()throws Exception{
        when(reauth.issue(ACCESS," clave ",WorkshopClosurePurpose.CERRAR,ID,ID)).thenReturn(new ReauthenticationGrant(PROOF,NOW.plusSeconds(300)));
        mvc.perform(post(WorkshopClosureHttpRequests.REAUTH).header("Authorization","Bearer "+ACCESS).contentType("application/json")
            .content(WorkshopClosureHttpRequestsTest.reauth(" clave "))).andExpect(status().isOk()).andExpect(jsonPath("$.token").value(PROOF))
            .andExpect(jsonPath("$.expiresAt").exists()).andExpect(jsonPath("$.reauthToken").doesNotExist()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(reauth).issue(ACCESS," clave ",WorkshopClosurePurpose.CERRAR,ID,ID);
        assertThat(new WorkshopClosureHttpController.ReauthenticationResponse(PROOF,NOW).toString()).doesNotContain(PROOF);
    }
    @Test void commandPreservesReplayReceiptAndPublishesNoDeletionDeadline()throws Exception{
        var receipt=new WorkshopClosureCommandService.Receipt(ID,ID,WorkshopClosurePurpose.CERRAR,1,"RESTRINGIDO",NOW,NOW.plus(Duration.ofDays(7)),NOW.plus(Duration.ofDays(37)),NOW,true);
        when(commands.execute(ACCESS,PROOF,WorkshopClosurePurpose.CERRAR,ID,ID,"CERRAR MI TALLER")).thenReturn(receipt);
        String json=mvc.perform(post(WorkshopClosureHttpRequests.COMMAND).header("Authorization","Bearer "+ACCESS).header("X-Reauth-Token",PROOF)
            .contentType("application/json").content(WorkshopClosureHttpRequestsTest.command("CERRAR MI TALLER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reutilizada").value(true)).andExpect(jsonPath("$.estadoResultante").value("RESTRINGIDO"))
            .andReturn().getResponse().getContentAsString();
        assertThat(new ObjectMapper().readTree(json).size()).isEqualTo(8);assertThat(json).doesNotContain("eliminacion","deletion","generation",PROOF);
    }
    @Test void malformedProofIsRejectedBeforeCommandAndUnauthenticatedDirectCallCannotBypassFilter()throws Exception{
        mvc.perform(post(WorkshopClosureHttpRequests.COMMAND).header("Authorization","Bearer "+ACCESS).header("X-Reauth-Token","malformed")
            .contentType("application/json").content(WorkshopClosureHttpRequestsTest.command("CERRAR MI TALLER")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REAUTENTICACION_INVALIDA"));verifyNoInteractions(commands);
        SecurityContextHolder.clearContext();assertThatThrownBy(()->controller.status(new MockHttpServletRequest("GET",WorkshopClosureHttpRequests.ROOT)))
            .isInstanceOf(WorkshopClosureHttpException.class);verifyNoInteractions(status);
    }
    @Test void serviceFailureIsSanitizedAndPasswordErrorsRetainTheirSafeCode()throws Exception{
        when(status.read(ACCESS)).thenThrow(new IllegalStateException("SQL jdbc-password internal-token"));
        String json=mvc.perform(get(WorkshopClosureHttpRequests.ROOT).header("Authorization","Bearer "+ACCESS))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CIERRE_NO_DISPONIBLE"))
            .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("jdbc-password","internal-token","SQL");
        when(reauth.issue(ACCESS," clave ",WorkshopClosurePurpose.CERRAR,ID,ID)).thenThrow(new BadRequestException("PASSWORD_ACTUAL_INVALIDA", "private"));
        mvc.perform(post(WorkshopClosureHttpRequests.REAUTH).header("Authorization","Bearer "+ACCESS).contentType("application/json")
            .content(WorkshopClosureHttpRequestsTest.reauth(" clave "))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_ACTUAL_INVALIDA"));
    }
    @Test void blockedStatusUsesTheExistingClosureCode()throws Exception{
        when(status.read(ACCESS)).thenThrow(new WorkshopClosureBlockedException());
        mvc.perform(get(WorkshopClosureHttpRequests.ROOT).header("Authorization","Bearer "+ACCESS))
            .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("CUENTA_EN_CIERRE"));
    }
}
