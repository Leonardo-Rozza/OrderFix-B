package com.leonardorozza.mvgrreparacionesbackend.config.filter;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtEmailVerificationAccessTest {
    private final Instant now = Instant.now();
    private final User actor = User.builder().id(10L).username("Pending").email("pending@synthetic.invalid")
            .password("fixture-hash").role(UserRole.ADMIN).active(true).emailVerificado(false).tokenVersion(1L)
            .taller(Taller.builder().id(20L).nombre("Fixture").activo(true).build()).build();
    private final JwtUtils jwt = new JwtUtils("verification-fixture-secret-at-least-32-bytes", 600_000, "test", "api", 0);

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    @ParameterizedTest @CsvSource({
            "GET,/api/perfil", "GET,/api/requisitos-legales", "GET,/api/aceptaciones-legales",
            "POST,/api/aceptaciones-legales", "POST,/api/cuenta/baja-acceso"
    })
    void exactAccountSelfServiceRemainsAvailableToBothRoles(String method, String path) throws Exception {
        for (UserRole role : UserRole.values()) {
            actor.setRole(role);
            SecurityContextHolder.clearContext();
            assertAdmitted(method, path, "");
        }
    }

    @Test void accountSelfServiceWorksUnderAnApplicationContext() throws Exception {
        assertAdmitted("GET", "/api/perfil", "/ordenfix");
    }

    @ParameterizedTest @CsvSource({"GET,/api/suscripcion", "POST,/api/pagos/suscripcion/cancelar"})
    void ownerCanInspectAndStopTheirExistingSubscriptionButEmployeeCannot(String method, String path) throws Exception {
        assertAdmitted(method, path, "");
        actor.setRole(UserRole.USER);
        SecurityContextHolder.clearContext();
        assertDenied(method, path);
    }

    @ParameterizedTest @CsvSource({
            "GET,/api/clientes", "POST,/api/clientes", "PUT,/api/clientes/1", "DELETE,/api/clientes/1",
            "GET,/api/dashboard", "GET,/api/equipos", "GET,/api/reparaciones", "GET,/api/reparaciones/1",
            "POST,/api/reparaciones/ingreso-rapido", "PATCH,/api/reparaciones/1/estado",
            "GET,/api/reparaciones/1/resumen-digital", "POST,/api/reparaciones/1/cobros",
            "GET,/api/usuarios", "POST,/api/usuarios", "GET,/api/taller/datos-cobro",
            "GET,/api/exportaciones/actual", "GET,/api/export/excel", "POST,/api/exportaciones",
            "POST,/api/pagos/suscripcion", "GET,/api/cuenta/cierre", "POST,/api/cuenta/cierre/operaciones",
            "POST,/api/perfil", "HEAD,/api/perfil", "GET,/api/perfil/", "GET,//api/perfil",
            "GET,/api/%70erfil", "GET,/api/perfil;ignored=true", "GET,/api/PERFIL",
            "GET,/api/perfil/child", "GET,/api/cuenta/baja-acceso", "POST,/api/cuenta/baja-acceso/",
            "GET,/api/aceptaciones-legales/child", "DELETE,/api/aceptaciones-legales",
            "POST,/api/pagos/suscripcion/cancelar/", "GET,/api/pagos/suscripcion/cancelar", "HEAD,/api/suscripcion"
    })
    void everyOtherPrivateOperationStopsBeforeTheHandler(String method, String path) throws Exception {
        assertDenied(method, path);
    }

    @Test void legalHistoryQueryValidationStaysWithItsController() throws Exception {
        var request = request("GET", "/api/aceptaciones-legales", "");
        request.setQueryString("page=0&size=20&contexto=USO_CONTINUADO");
        var reached = new AtomicBoolean();
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
    }

    @Test void confirmationBecomesEffectiveForTheSameJwtOnTheNextRequest() throws Exception {
        var request = request("GET", "/api/clientes", "");
        var denied = new MockHttpServletResponse();
        filter().doFilter(request, denied, (req, res) -> { throw new AssertionError("pending account reached business data"); });
        assertThat(denied.getStatus()).isEqualTo(403);
        actor.setEmailVerificado(true);
        SecurityContextHolder.clearContext();
        var admitted = new AtomicBoolean();
        filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> admitted.set(true));
        assertThat(admitted).isTrue();
        assertThat(TenantContext.getTallerId()).isNull();
    }

    @ParameterizedTest @EnumSource(UserRole.class)
    void verifiedUsersKeepTheirExistingBusinessAccessForTheSecurityChainToAuthorize(UserRole role) throws Exception {
        actor.setRole(role);
        actor.setEmailVerificado(true);
        assertAdmitted("POST", "/api/clientes", "");
    }

    @ParameterizedTest @CsvSource({
            "POST,/api/auth/login", "POST,/api/auth/verificar-email", "POST,/api/auth/verificar-email/reenviar",
            "POST,/api/auth/password/olvide", "POST,/api/auth/password/reset", "GET,/api/seguimiento/PUBLICCODE"
    })
    void existingPublicRecoveryAndTrackingRoutesRemainPublic(String method, String path) throws Exception {
        var reached = new AtomicBoolean();
        filter().doFilter(request(method, path, ""), new MockHttpServletResponse(), (req, res) -> {
            reached.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        });
        assertThat(reached).isTrue();
    }

    private void assertAdmitted(String method, String path, String context) throws Exception {
        var reached = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter().doFilter(request(method, path, context), response, (req, res) -> {
            reached.set(true);
            assertThat(TenantContext.getTallerId()).isEqualTo(20L);
        });
        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.getTallerId()).isNull();
    }

    private void assertDenied(String method, String path) throws Exception {
        var reached = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter().doFilter(request(method, path, ""), response, (req, res) -> reached.set(true));
        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"code\":\"EMAIL_NO_VERIFICADO\"", "Verificá tu email para continuar.")
                .doesNotContain(actor.getEmail(), "fixture-hash");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(TenantContext.getTallerId()).isNull();
    }

    private JwtFilter filter() {
        var users = mock(UserRepository.class);
        when(users.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        return new JwtFilter(jwt, new UserDetailsServiceImpl(users, Clock.fixed(now, ZoneOffset.UTC)));
    }

    private MockHttpServletRequest request(String method, String path, String context) {
        var request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context);
        request.setServletPath(path);
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(new AuthenticatedUserPrincipal(actor, now), 20L));
        return request;
    }
}
