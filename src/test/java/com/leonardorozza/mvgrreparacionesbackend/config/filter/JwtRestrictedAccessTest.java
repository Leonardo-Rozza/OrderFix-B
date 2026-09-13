package com.leonardorozza.mvgrreparacionesbackend.config.filter;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosurePolicy;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHistoryController;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalAcceptanceHistoryExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryPage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceHistoryService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.Mockito.when;

class JwtRestrictedAccessTest {
    private static final String JOB = "4b306acb-ce32-47ca-b18d-dce56c3ed255";
    private final Instant now = Instant.now();
    private final User actor = actor(now);
    private final JwtUtils jwt = new JwtUtils("closure-fixture-security-key-at-least-32-bytes", 600_000, "closure-test", "api", 0);

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    @ParameterizedTest @CsvSource({
        "GET,/api/perfil", "GET,/api/aceptaciones-legales", "GET,/api/exportaciones/actual",
        "GET,/api/exportaciones/4b306acb-ce32-47ca-b18d-dce56c3ed255",
        "POST,/api/cuenta/reauthenticaciones", "POST,/api/exportaciones/4b306acb-ce32-47ca-b18d-dce56c3ed255/archivo"
    })
    void exactMethodAndPathAdmitRestrictedIdentity(String method, String path) throws Exception {
        assertAdmitted(method, path, "");
    }

    @Test void exactAdmissionWorksWithAnApplicationContextPrefix() throws Exception {
        assertAdmitted("POST", "/api/exportaciones/" + JOB + "/archivo", "/ordenfix");
    }

    @ParameterizedTest @CsvSource({
        "POST,/api/exportaciones", "GET,/api/export/excel", "GET,/api/clientes", "PATCH,/api/perfil",
        "POST,/api/cuenta/baja-acceso", "POST,/api/cuenta/cierre", "GET,/api/cuenta/reauthenticaciones",
        "HEAD,/api/perfil", "OPTIONS,/api/perfil", "POST,/api/aceptaciones-legales", "GET,/api/perfil/",
        "GET,/api/perfil;ignored=true", "GET,/api/%70erfil", "GET,//api/perfil", "GET,/api/PERFIL",
        "GET,/api/exportaciones/4B306ACB-CE32-47CA-B18D-DCE56C3ED255", "GET,/api/exportaciones/not-a-uuid",
        "POST,/api/exportaciones/4b306acb-ce32-47ca-b18d-dce56c3ed255/archivo/",
        "GET,/api/exportaciones/4b306acb-ce32-47ca-b18d-dce56c3ed255/archivo"
    })
    void everyOtherOperationStopsBeforeTheHandlerWithTheClosureContract(String method, String path) throws Exception {
        var request = request(method, path, "");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter().doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(response.getContentAsString()).contains("\"code\":\"CUENTA_EN_CIERRE\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @Test void queryCannotBroadenAnAdmittedPath() throws Exception {
        var request = request("GET", "/api/perfil", "");
        request.setQueryString("operation=update");
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach handler"); });
        assertThat(response.getStatus()).isEqualTo(423);
    }

    @ParameterizedTest @CsvSource({"0,20,", "2,10,USO_CONTINUADO", "2147483647,100,CIERRE_CUENTA"})
    void restrictedHistoryKeepsTheControllersPaginationAndContextContract(int page, int size, String context) throws Exception {
        var service = mock(LegalAcceptanceHistoryService.class);
        ContextoLegal expectedContext = context == null ? null : ContextoLegal.valueOf(context);
        when(service.read(any(AuthenticatedUserPrincipal.class), nullable(ContextoLegal.class), eq(page), eq(size)))
                .thenReturn(new LegalAcceptanceHistoryPage(List.of(), page, size, 0, 0));
        var request = get("/api/aceptaciones-legales").queryParam("page", Integer.toString(page))
                .queryParam("size", Integer.toString(size)).header("Authorization", bearer());
        if (context != null) request.queryParam("contexto", context);
        historyMvc(service).perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        if (expectedContext == null) verify(service).read(any(AuthenticatedUserPrincipal.class), isNull(), eq(page), eq(size));
        else verify(service).read(any(AuthenticatedUserPrincipal.class), eq(expectedContext), eq(page), eq(size));
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "oversized", "repeated"})
    void historyQueryErrorsRemainController400WithoutReadingAnyEvidence(String invalid) throws Exception {
        var service = mock(LegalAcceptanceHistoryService.class);
        var request = get("/api/aceptaciones-legales").header("Authorization", bearer());
        switch (invalid) {
            case "actor" -> request.queryParam("tallerId", "999");
            case "oversized" -> request.queryParam("size", "101");
            default -> request.queryParam("page", "0", "1");
        }
        historyMvc(service).perform(request).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @ParameterizedTest @CsvSource({
        "POST,/api/aceptaciones-legales", "HEAD,/api/aceptaciones-legales", "GET,/api/aceptaciones-legales/",
        "GET,/api/aceptaciones-legales/child", "GET,/api/perfil", "GET,/api/exportaciones/actual",
        "POST,/api/cuenta/reauthenticaciones"
    })
    void historyQueryAdmissionDoesNotEnableOtherMethodsOrPaths(String method, String path) throws Exception {
        var service = mock(LegalAcceptanceHistoryService.class);
        historyMvc(service).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(HttpMethod.valueOf(method), path).queryParam("page", "0").queryParam("size", "20")
                        .header("Authorization", bearer()))
                .andExpect(status().isLocked()).andExpect(header().string("Cache-Control", "private, no-store"));
        verifyNoInteractions(service);
    }

    private MockMvc historyMvc(LegalAcceptanceHistoryService service) {
        return MockMvcBuilders.standaloneSetup(new LegalAcceptanceHistoryController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new LegalAcceptanceHistoryExceptionHandler()).addFilters(filter()).build();
    }

    private String bearer() { return "Bearer " + jwt.generateToken(new AuthenticatedUserPrincipal(actor, now), 20L); }

    @Test void openWorkshopKeepsItsOperativeRoutes() throws Exception {
        actor.getTaller().setCierreEstado("ABIERTO");
        assertAdmitted("POST", "/api/clientes", "");
    }

    @Test void everyRequestReloadsTheEpochAndRestorationCannotReviveTheOldJwt() throws Exception {
        var request = request("GET", "/api/perfil", "");
        actor.setTokenVersion(actor.getTokenVersion() + 1);
        actor.getTaller().setCierreEstado("ABIERTO");
        var reached = new AtomicBoolean();
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            reached.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantContext.getTallerId()).isNull();
        });
        assertThat(reached).isTrue(); // The security chain handles unauthenticated rejection.
    }

    @Test void restrictedEmployeeCannotAuthenticateEvenWithACurrentSignedJwt() throws Exception {
        var request = request("GET", "/api/perfil", "");
        actor.setRole(UserRole.USER);
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
    }

    private void assertAdmitted(String method, String path, String context) throws Exception {
        var reached = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter().doFilter(request(method, path, context), response, (req, res) -> {
            reached.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(AuthenticatedUserPrincipal.class);
            assertThat(TenantContext.getTallerId()).isEqualTo(20L);
        });
        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.getTallerId()).isNull();
    }

    private JwtFilter filter() {
        var users = mock(UserRepository.class);
        when(users.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        return new JwtFilter(jwt, new UserDetailsServiceImpl(users, Clock.fixed(now, ZoneOffset.UTC)));
    }

    private MockHttpServletRequest request(String method, String path, String context) {
        var request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context); request.setServletPath(path);
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(new AuthenticatedUserPrincipal(actor, now), 20L));
        return request;
    }

    private static User actor(Instant now) {
        var workshop = Taller.builder().id(20L).nombre("Fixture").activo(true).build();
        var schedule = WorkshopClosurePolicy.scheduleAt(now.minusSeconds(30));
        workshop.setCierreEstado("RESTRINGIDO"); workshop.setCierreVersion(1); workshop.setCierreReferencia(UUID.fromString(JOB));
        workshop.setCierreConfirmadoEn(schedule.confirmedAt().atOffset(ZoneOffset.UTC));
        workshop.setCierreReversibleHasta(schedule.reversibleUntil().atOffset(ZoneOffset.UTC));
        workshop.setCierreEliminacionPrevistaEn(schedule.deletionExpectedBy().atOffset(ZoneOffset.UTC));
        return User.builder().id(10L).username("Fixture").email("closure@example.test").password("fixture-hash")
                .role(UserRole.ADMIN).active(true).emailVerificado(true).tokenVersion(7).taller(workshop).build();
    }
}
