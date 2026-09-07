package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Membership;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Scope;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAuthenticatedRequirements.Snapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.DocumentProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentReference;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.DocumentVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.EvidenceDocument;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementLine;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementLineage.RequirementVersion;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequirementSatisfactionEvaluator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalActorSnapshotException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadException;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalPrivateRequirementsControllerTest {

    private static final String BASE = "/api/requisitos-legales";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextoLegal USE = ContextoLegal.USO_CONTINUADO;
    private static final ContextoLegal CLOSE = ContextoLegal.CIERRE_CUENTA;
    private LegalPrivateRequirementsReadService service;
    private AuthenticatedUserPrincipal principal;
    private MockMvc mvc;

    @BeforeEach
    void buildPrivateTransportAlongsideHistoricalAdvice() {
        service = mock(LegalPrivateRequirementsReadService.class);
        principal = principal(UserRole.ADMIN);
        authenticate(principal);
        mvc = standalone(new LegalPrivateRequirementsController(service));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void fullPrivateWirePreservesCompleteRevisionAndPendingOrderForEachRole(UserRole role) throws Exception {
        principal = principal(role);
        authenticate(principal);
        LegalAuthenticatedRequirements source = projection(role, Set.of());
        when(service.read(principal)).thenReturn(source);

        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        JsonNode wire = body(result);
        assertThat(fields(wire)).containsExactlyInAnyOrder("locale", "requiredSetRevision", "requisitos");
        assertThat(wire.path("locale").textValue()).isEqualTo("es-AR");
        assertThat(wire.path("requiredSetRevision").textValue()).isEqualTo(source.requiredSetRevision())
                .startsWith("sha256:");
        assertThat(wire.path("requisitos").size()).isEqualTo(3);
        assertThat(wire.path("requisitos").get(0).path("id").textValue()).isEqualTo(id(30).toString());
        assertThat(wire.path("requisitos").get(1).path("id").textValue()).isEqualTo(id(10).toString());
        assertThat(wire.path("requisitos").get(2).path("id").textValue()).isEqualTo(id(20).toString());
        assertThat(wire.path("requisitos").get(1).path("requerido").booleanValue()).isFalse();
        assertThat(wire.path("requisitos").get(2).path("contexto").textValue()).isEqualTo("CIERRE_CUENTA");
        for (JsonNode requirement : wire.path("requisitos")) {
            assertThat(fields(requirement)).containsExactlyInAnyOrder("id", "contexto", "tipoActo", "afirmacion",
                    "afirmacionSha256", "requerido", "documentos");
            assertThat(requirement.path("afirmacionSha256").textValue())
                    .isEqualTo(sha(requirement.path("afirmacion").textValue()));
            for (JsonNode document : requirement.path("documentos")) {
                assertThat(fields(document)).containsExactlyInAnyOrder("id", "tipo", "version", "titulo",
                        "contenidoMarkdown", "sha256", "vigenteDesde", "estado", "locale");
                assertThat(document.path("estado").textValue()).isEqualTo("VIGENTE");
                assertThat(document.path("locale").textValue()).isEqualTo("es-AR");
                assertThat(document.path("sha256").textValue())
                        .isEqualTo(sha(document.path("contenidoMarkdown").textValue()));
            }
        }
        JsonNode firstDocuments = wire.path("requisitos").get(0).path("documentos");
        assertThat(firstDocuments.get(0).path("id").textValue()).isEqualTo(id(200).toString());
        assertThat(firstDocuments.get(1).path("id").textValue()).isEqualTo(id(100).toString());
        assertThat(firstDocuments.get(0).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-06T03:00:00.123456Z");
        assertThat(firstDocuments.get(1).path("vigenteDesde").textValue())
                .isEqualTo("2026-09-06T03:00:00Z");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("userId", "tallerId", "tokenVersion",
                "audience", "profile", "scopeRevision", "acceptanceId", "decisions", "requiresReacceptance");
        verify(service).read(principal);
    }

    @Test
    void satisfactionFiltersOnlyPendingItemsWithoutRecalculatingTheCompleteRevision() throws Exception {
        LegalAuthenticatedRequirements complete = projection(UserRole.ADMIN, Set.of());
        LegalAuthenticatedRequirements pending = projection(UserRole.ADMIN, Set.of(id(30), id(20)));
        when(service.read(principal)).thenReturn(pending);

        JsonNode wire = body(mvc.perform(get(BASE)).andExpect(status().isOk()).andReturn());

        assertThat(wire.path("requiredSetRevision").textValue()).isEqualTo(complete.requiredSetRevision());
        assertThat(pending.hasRequiredPending()).isFalse();
        assertThat(wire.path("requisitos").size()).isOne();
        assertThat(wire.path("requisitos").get(0).path("id").textValue()).isEqualTo(id(10).toString());
        assertThat(wire.path("requisitos").get(0).path("requerido").booleanValue()).isFalse();
        verify(service).read(principal);
    }

    @Test
    void legitimatelyEmptyPendingListRetainsTheNonemptyCompleteAggregateRevision() throws Exception {
        LegalAuthenticatedRequirements complete = projection(UserRole.ADMIN, Set.of());
        LegalAuthenticatedRequirements satisfied = projection(UserRole.ADMIN, Set.of(id(30), id(10), id(20)));
        when(service.read(principal)).thenReturn(satisfied);

        JsonNode wire = body(mvc.perform(get(BASE).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertThat(wire.path("requisitos").isArray()).isTrue();
        assertThat(wire.path("requisitos").size()).isZero();
        assertThat(wire.path("requiredSetRevision").textValue()).isEqualTo(complete.requiredSetRevision());
        verify(service).read(principal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "strong", "list", "wildcard", "multiple", "malformed"})
    void conditionalRequestsAlwaysReadAndReturnTheComplete200WithoutEtag(String mode) throws Exception {
        LegalAuthenticatedRequirements source = projection(UserRole.ADMIN, Set.of());
        when(service.read(principal)).thenReturn(source);
        String tag = "\"" + source.requiredSetRevision() + "\"";
        String[] condition = switch (mode) {
            case "weak" -> new String[]{"W/" + tag};
            case "strong" -> new String[]{tag};
            case "list" -> new String[]{"\"old\", W/" + tag};
            case "wildcard" -> new String[]{"*"};
            case "multiple" -> new String[]{"\"old\"", "W/" + tag};
            default -> new String[]{"\"unterminated"};
        };

        JsonNode wire = body(mvc.perform(get(BASE).header(HttpHeaders.IF_NONE_MATCH, (Object[]) condition)
                        .header(HttpHeaders.IF_MATCH, "\"old\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn());

        assertThat(wire.path("requiredSetRevision").textValue()).isEqualTo(source.requiredSetRevision());
        assertThat(wire.path("requisitos").size()).isEqualTo(3);
        verify(service).read(principal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "userId", "tallerId", "tenant", "perfil", "audiencia", "rol", "contextos",
            "contexto", "locale", "unknown"})
    void noQueryParameterCanSelectOrAlterTheServerObservation(String parameter) throws Exception {
        MvcResult result = mvc.perform(get(BASE).queryParam(parameter, "private-secret", "other-actor")
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isBadRequest()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        assertGenericError(result, 400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-secret", "other-actor");
        verifyNoInteractions(service);
    }

    @Test
    void emptyQueryValueIsStillAnUnsupportedSelector() throws Exception {
        mvc.perform(get(BASE).queryParam("locale", "")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void errorPathRetainsDeploymentContextWithoutEchoingQueryValues() throws Exception {
        MvcResult result = mvc.perform(get("/ordenfix" + BASE).contextPath("/ordenfix")
                        .queryParam("actor", "secret-selector"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(body(result).path("path").textValue()).isEqualTo("/ordenfix" + BASE);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-selector", "?");
        verifyNoInteractions(service);
    }

    @Test
    void springImplicitHeadDoesNotReadOrMaterializeTheAggregate() throws Exception {
        mvc.perform(head(BASE)).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsupportedMethodsCannotReachTheReader(String method) throws Exception {
        // Preserve the existing global MVC handler for requests with no matching endpoint.
        mvc.perform(request(HttpMethod.valueOf(method), BASE)).andExpect(status().isInternalServerError());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/child", "-otro"})
    void onlyTheExactPrivateRouteIsMapped(String suffix) throws Exception {
        // Missing mappings are currently converted to 500 by GlobalExceptionHandler.
        mvc.perform(get(BASE + suffix)).andExpect(status().isInternalServerError());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/requisitos%2dlegales", "/api/requisitos%2Dlegales"})
    void anEncodedNeighborDecodedByMvcCannotMaterializeThePrivateAggregate(String path) throws Exception {
        MvcResult result = mvc.perform(get(URI.create(path))).andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertThat(body(result).path("path").textValue()).isEqualTo(path);
        assertThat(body(result).has("code")).isFalse();
        verifyNoInteractions(service);
    }

    @Test
    void missingPrincipalIsUnauthorizedBeforeTheFacade() throws Exception {
        SecurityContextHolder.clearContext();
        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();
        assertGenericError(result, 401);
        verifyNoInteractions(service);
    }

    @Test
    void genericAuthenticatedUserCannotStandInForTheServerPrincipal() throws Exception {
        var generic = org.springframework.security.core.userdetails.User.withUsername("other@example.invalid")
                .password("irrelevant").roles("ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                generic, null, generic.getAuthorities()));

        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andReturn();
        assertGenericError(result, 401);
        verifyNoInteractions(service);
    }

    @Test
    void staleOrForeignLockedActorIsUnauthorizedWithoutInternalIdentityDetails() throws Exception {
        LegalActorSnapshotException failure = new LegalActorSnapshotException();
        when(service.read(principal)).thenThrow(failure);

        MvcResult result = mvc.perform(get(BASE).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        assertGenericError(result, 401);
        assertThat(result.getResolvedException()).hasCause(failure);
        verify(service).read(principal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader", "unexpected", "null", "mapping"})
    void everyUnavailableReadOrProjectionFailsClosedWithThePrivate503Contract(String mode) throws Exception {
        RuntimeException failure = new LegalPrivateRequirementsReadException(
                new SQLException("SELECT secret_password FROM /private/internal_table"));
        if (mode.equals("reader")) {
            when(service.read(principal)).thenThrow(failure);
        } else if (mode.equals("unexpected")) {
            when(service.read(principal)).thenThrow(new IllegalStateException("/private/internal_table"));
        } else if (mode.equals("mapping")) {
            LegalAuthenticatedRequirements broken = mock(LegalAuthenticatedRequirements.class);
            when(broken.snapshot()).thenThrow(new IllegalStateException("/private/internal_table"));
            when(service.read(principal)).thenReturn(broken);
        } else {
            when(service.read(principal)).thenReturn(null);
        }

        MvcResult result = mvc.perform(get(BASE).header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

        JsonNode error = body(result);
        assertThat(fields(error)).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path",
                "code", "details");
        assertThat(error.path("status").intValue()).isEqualTo(503);
        assertThat(error.path("code").textValue()).isEqualTo("CONTRATO_LEGAL_NO_DISPONIBLE");
        assertThat(error.path("message").textValue()).isEqualTo("El contrato legal no está disponible.");
        assertThat(error.path("details")).isEqualTo(JSON.readTree("{\"contexto\":null,\"locale\":\"es-AR\"}"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SELECT", "secret_password", "/private",
                "SQLException", "stackTrace", "cause", "REGISTRO");
        verify(service).read(principal);
    }

    @Test
    void methodSecurityDeniesAnAuthenticatedUnsupportedRoleBeforeAnyObservation() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("private-http-test",
                    Map.of("ordenfix.legal.account-read.enabled", "true")));
            context.register(MethodSecurity.class);
            context.registerBean(LegalPrivateRequirementsReadService.class, () -> service);
            context.registerBean(LegalPrivateRequirementsController.class,
                    () -> new LegalPrivateRequirementsController(service));
            context.refresh();
            var outsider = org.springframework.security.core.userdetails.User.withUsername("other@example.invalid")
                    .password("irrelevant").roles("AUDITOR").build();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    outsider, null, outsider.getAuthorities()));

            MvcResult result = standalone(context.getBean(LegalPrivateRequirementsController.class))
                    .perform(get(BASE)).andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().doesNotExist(HttpHeaders.ETAG)).andReturn();

            assertGenericError(result, 403);
            verifyNoInteractions(service);
        }
    }

    @Test
    void privateAdviceClearsAnyEarlierSuccessTagAndNeverPublishesSecurityMessages() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BASE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(HttpHeaders.ETAG, "W/\"previous-success\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public");

        var result = new LegalPrivateRequirementsExceptionHandler().forbidden(
                new AccessDeniedException("private-actor-and-permission"), request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).doesNotContain("private-actor-and-permission");
        assertThat(result.getBody().getCode()).isNull();
        assertThat(result.getBody().getDetails()).isNull();
    }

    private static MockMvc standalone(LegalPrivateRequirementsController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new LegalPrivateRequirementsExceptionHandler())
                .build();
    }

    private static void authenticate(AuthenticatedUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static AuthenticatedUserPrincipal principal(UserRole role) {
        return new AuthenticatedUserPrincipal(User.builder().id(51L).email("actor@example.invalid")
                .username("Actor").password("irrelevant").role(role).active(true).tokenVersion(7L)
                .taller(Taller.builder().id(72L).nombre("Taller").activo(true).build()).build());
    }

    /** Real pure evaluator fixture: the transport receives an already validated multicontext observation. */
    private static LegalAuthenticatedRequirements projection(UserRole role, Set<UUID> accepted) throws Exception {
        DocumentProjection terms = document(200, "2026-09-06T00:00:00.123456-03:00");
        DocumentProjection privacy = document(100, "2026-09-06T00:00:00-03:00");
        RequirementProjection required = requirement(30, USE, true, List.of(terms, privacy));
        RequirementProjection optional = requirement(10, USE, false, List.of(privacy));
        RequirementProjection close = requirement(20, CLOSE, true, List.of(terms));
        List<Scope> scopes = List.of(
                new Scope(1, new LegalRequiredSetProjection(USE, LocaleLegal.ES_AR, List.of(required, optional)),
                        List.of(new Membership(3, "required-use", required.versionId()),
                                new Membership(17, "optional-use", optional.versionId()))),
                new Scope(2, new LegalRequiredSetProjection(CLOSE, LocaleLegal.ES_AR, List.of(close)),
                        List.of(new Membership(2, "account-close", close.versionId()))));
        var applicable = new LegalApplicableScopeResolver((profile, audience) -> List.of(USE, CLOSE))
                .resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING, LocaleLegal.ES_AR, role.toAudienciaLegal());
        Snapshot snapshot = new Snapshot(new LegalActorSnapshot(51, 72, role, 7, true, true), applicable, scopes);

        Map<UUID, DocumentLine> documents = new LinkedHashMap<>();
        List<RequirementLine> requirements = new ArrayList<>();
        List<Acceptance> evidence = new ArrayList<>();
        for (Scope scope : scopes) {
            for (int index = 0; index < scope.projection().requirements().size(); index++) {
                RequirementProjection requirement = scope.projection().requirements().get(index);
                List<DocumentReference> references = new ArrayList<>();
                for (DocumentProjection document : requirement.documents()) {
                    String key = "document-" + document.versionId().getLeastSignificantBits();
                    documents.computeIfAbsent(document.versionId(), ignored -> new DocumentLine(
                            id(1000 + document.versionId().getLeastSignificantBits()), key, document.locale(),
                            document.type(), List.of(new DocumentVersion(document.versionId(), 1,
                                    EstadoVersionLegal.VIGENTE, false, document.sha256()))));
                    references.add(new DocumentReference(key, document.versionId(), document.sha256()));
                }
                requirements.add(new RequirementLine(id(2000 + requirement.versionId().getLeastSignificantBits()),
                        scope.memberships().get(index).requirementKey(), requirement.context(), LocaleLegal.ES_AR,
                        requirement.actType(), Set.of(role.toAudienciaLegal()),
                        List.of(new RequirementVersion(requirement.versionId(), 1, EstadoVersionLegal.VIGENTE,
                                false, requirement.statementSha256(), requirement.required(), references))));
                if (accepted.contains(requirement.versionId())) {
                    evidence.add(new Acceptance(id(3000 + requirement.versionId().getLeastSignificantBits()),
                            51, 72, role, requirement.versionId(), requirement.statementSha256(),
                            references.stream().map(reference -> new EvidenceDocument(reference.key(),
                                    reference.versionId(), reference.sha256())).toList(),
                            Instant.parse("2026-09-06T04:00:00Z")));
                }
            }
        }
        return new LegalRequirementSatisfactionEvaluator().evaluate(snapshot,
                new LegalRequirementLineage(requirements, List.copyOf(documents.values())), evidence);
    }

    private static RequirementProjection requirement(long version, ContextoLegal context, boolean required,
                                                       List<DocumentProjection> documents) throws Exception {
        String statement = "Acepto la afirmación " + version + ".";
        return new RequirementProjection(id(version), context, TipoActoLegal.ACEPTACION,
                statement, sha(statement), documents, required);
    }

    private static DocumentProjection document(long version, String effectiveAt) throws Exception {
        String markdown = "# Documento " + version + "\n\nTexto legal de prueba.\n";
        return new DocumentProjection(id(version), version == 200 ? TipoDocumentoLegal.TERMINOS_SERVICIO
                : TipoDocumentoLegal.POLITICA_PRIVACIDAD, "v1", "Documento " + version, markdown, sha(markdown),
                OffsetDateTime.parse(effectiveAt), LocaleLegal.ES_AR);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertGenericError(MvcResult result, int status) throws Exception {
        JsonNode error = body(result);
        assertThat(fields(error)).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        assertThat(error.path("timestamp").isTextual()).isTrue();
        assertThat(error.path("status").intValue()).isEqualTo(status);
        assertThat(error.path("message").textValue()).isNotBlank();
        assertThat(error.path("error").textValue()).isNotBlank();
        assertThat(error.path("path").textValue()).isEqualTo(BASE);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> fields(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity { }
}
