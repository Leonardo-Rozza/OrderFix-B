package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicDocumentHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPublicRequirementsHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalRegistrationHttpConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalIdempotencyFingerprint;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegacyRegistrationAccountWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: the name intentionally matches neither the ordinary *Test nor *IT gates.
 * Owns real PostgreSQL, the complete Boot/Tomcat application and a real browser subprocess.
 * No API response, account service, JDBC operation, password comparison or JWT is mocked. */
@SpringBootTest(classes = MvgrReparacionesBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.config.additional-location=",
                "spring.config.location=optional:classpath:/application.properties"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LegalRegistrationBrowserE2E {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOC_ROLE = "registration_browser_documents";
    private static final String DOC_PASSWORD = "registration-browser-documents-fixture";
    private static final String FRONTEND_ORIGIN = "http://127.0.0.1:5175";
    private static final String PASSWORD = "Clave-sintetica-E2E-123";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final List<String> BUSINESS_TABLES = List.of("users", "talleres", "suscripciones", "auth_tokens",
            "clientes", "equipos", "reparaciones", "repuestos", "reparacion_fotos", "presupuestos", "presupuesto_items",
            "articulos", "cobros", "subscription_provider_links", "payment_events", "subscription_payments", "taller_qr_cobro");
    private static final List<String> COUNTED_TABLES = List.of("users", "talleres", "suscripciones", "auth_tokens", "clientes",
            "equipos", "reparaciones", "presupuestos", "presupuesto_items",
            "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos", "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos");
    private static final List<String> UNCHANGED_TABLES = List.of("articulos", "repuestos", "cobros",
            "subscription_provider_links", "payment_events", "subscription_payments", "taller_qr_cobro");
    private static LegalRegistrationHttpITSupport fixture;
    private static Path publicationDirectory;

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_browser").withUsername("ordenfix").withPassword("ordenfix");

    @Autowired ConfigurableApplicationContext application;
    @Autowired JdbcTemplate applicationJdbc;
    @LocalServerPort int port;
    @TempDir Path evidenceDirectory;

    @DynamicPropertySource static synchronized void properties(DynamicPropertyRegistry registry) throws Exception {
        if (fixture == null) {
            fixture = new LegalRegistrationHttpITSupport(POSTGRES);
            publicationDirectory = Files.createTempDirectory("ordenfix-registration-browser-publication-");
            fixture.reset(publicationDirectory, LegalRegistrationBrowserE2E.class);
            extendOnlyTheEphemeralApplicationRole();
            provisionDocumentRole();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        values.put("spring.datasource.username", LegalRegistrationHttpITSupport.APP_ROLE);
        values.put("spring.datasource.password", LegalRegistrationHttpITSupport.APP_PASSWORD);
        values.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        values.put("spring.datasource.hikari.maximum-pool-size", "4");
        values.put("spring.datasource.hikari.minimum-idle", "0");
        values.put("spring.flyway.enabled", "false"); // Owner fixture has already migrated and verified the frozen schema.
        values.put("spring.jpa.hibernate.ddl-auto", "validate");
        values.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        values.put("spring.jpa.open-in-view", "false");
        values.put("server.address", "127.0.0.1");
        values.put("server.shutdown", "immediate");
        values.put("server.forward-headers-strategy", "none");
        values.put("server.tomcat.remoteip.protocol-header", "");
        values.put("server.tomcat.remoteip.remote-ip-header", "");
        values.put("app.cors.allowed-origins", FRONTEND_ORIGIN);
        values.put("app.public-url", FRONTEND_ORIGIN);
        values.put("security.rate-limit.enabled", "true");
        values.put("security.rate-limit.trust-forwarded-headers", "false");
        values.put("security.rate-limit.register.requests", "20");
        values.put("security.rate-limit.register.window", "1h");
        values.put("security.jwt.secret", LegalRegistrationHttpITSupport.JWT_SECRET);
        values.put("security.jwt.issuer", "ordenfix-registration-browser");
        values.put("security.jwt.audience", "ordenfix-registration-browser-api");
        values.put("security.jwt.expiration", "3600000");
        values.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", Base64.getEncoder().encodeToString("dddddddddddddddddddddddddddddddd".getBytes(StandardCharsets.US_ASCII)));
        values.put("mail.enabled", "false");
        values.put("mercadopago.enabled", "false");
        values.put("mercadopago.checkout-enabled", "false");
        values.put("admin.user", "Browser baseline");
        values.put("admin.email", "browser-baseline@ordenfix-e2e.test");
        values.put("admin.password", "Browser-baseline-synthetic-123");
        values.put("plan.trial-dias", "14");
        values.put("plan.free.max-reparaciones-mes", "25"); // Explicit production default; test properties otherwise use 50.
        values.put("ordenfix.legal.account-read.enabled", "false");
        values.put("ordenfix.legal.account-acceptance.enabled", "false");
        values.put(LegalRegistrationHttpConfiguration.CONSENT_PROPERTY, "true");
        values.put(LegalRegistrationHttpConfiguration.ENFORCEMENT_PROPERTY, "true");
        values.put(LegalPublicDocumentHttpConfiguration.ENABLED_PROPERTY, "true");
        values.put(LegalPublicRequirementsHttpConfiguration.ENABLED_PROPERTY, "true");
        credentials(values, LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX,
                fixture.fixture.writerFixture.credentials.username(), fixture.fixture.writerFixture.credentials.password());
        credentials(values, LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX,
                LegalRegistrationHttpITSupport.PUBLIC_ROLE, LegalRegistrationHttpITSupport.PUBLIC_PASSWORD);
        credentials(values, LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX, DOC_ROLE, DOC_PASSWORD);
        values.put(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "keyring.1", LegalRegistrationWriterITSupport.HMAC);
        values.put(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "active-write-version", "1");
        values.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "keyring.7", LegalRegistrationWriterITSupport.AES);
        values.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "active-write-version", "7");
        values.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "retention", "P30D");
        values.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "trusted-proxy-cidrs", "");
        values.forEach((key, value) -> registry.add(key, () -> value));
    }

    @Test void browserVerifiesRegistrationEmployeesAndRepairDelivery() throws Exception {
        Path frontend = Path.of(System.getProperty("ordenfix.browser.frontend", "../mvgr-reparaciones-frontend")).toRealPath();
        assertThat(frontend.resolve("package.json")).isRegularFile();
        assertThat(frontend.resolve("playwright.registration-real.config.ts")).isRegularFile();
        assertThat(port).isPositive();
        assertPhysicalRoles();
        Map<String, Identity> repairAccounts = prepareFreeRepairAccounts();
        Map<String, Long> baseline = counts();
        Map<String, List<String>> unchangedRows = unchangedRows();
        assertThat(baseline.get("users")).as("DataLoader baseline exists before any browser request").isPositive();
        BaselineAccount otherWorkshop = baselineAccount();
        Instant started = fixture.owner.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        Path report = evidenceDirectory.resolve("browser-evidence.json");
        Path log = evidenceDirectory.resolve("playwright.log");
        ProcessBuilder builder = new ProcessBuilder("npm", "exec", "--", "playwright", "test", "--config=playwright.registration-real.config.ts")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ORDENFIX_REGISTRATION_E2E_API_URL", "http://127.0.0.1:" + port);
        builder.environment().put("ORDENFIX_REGISTRATION_E2E_RUN_ID", RUN_ID);
        builder.environment().put("ORDENFIX_REGISTRATION_E2E_REPORT", report.toString());
        Process process = builder.start();
        Map<Long, OwnedProcess> descendants = new LinkedHashMap<>();
        Throwable primary = null;
        try {
            long until = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            boolean finished = false;
            while (System.nanoTime() < until) {
                rememberDescendants(process, descendants);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            rememberDescendants(process, descendants);
            assertThat(finished).as("Playwright exceeded five minutes.\n%s", tail(log)).isTrue();
            assertThat(process.exitValue()).as("Playwright failed.\n%s", tail(log)).isZero();
            Instant finishedAt = fixture.owner.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
            verifyReport(report, baseline, otherWorkshop, repairAccounts, unchangedRows, started, finishedAt);
        } catch (Throwable failure) {
            primary = failure; throw failure;
        } finally {
            try { stopOwnedProcesses(process, descendants); }
            catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    private void verifyReport(Path report, Map<String, Long> baseline, BaselineAccount otherWorkshop,
                              Map<String, Identity> repairAccounts, Map<String, List<String>> unchangedRows,
                              Instant started, Instant finished) throws Exception {
        assertThat(report).isRegularFile();
        assertThat(Files.size(report)).isBetween(1L, 262_144L);
        JsonNode entries = JSON.readTree(Files.readAllBytes(report));
        assertThat(entries.isArray()).isTrue();
        assertThat(entries).hasSize(12);
        List<String> scenarios = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        Set<Long> users = new HashSet<>(), workshops = new HashSet<>(), employees = new HashSet<>(), clients = new HashSet<>();
        Set<Long> repairClients = new HashSet<>(), equipment = new HashSet<>(), repairs = new HashSet<>(), budgets = new HashSet<>();
        Set<String> trackingCodes = new HashSet<>();
        int acts = 0, documents = 0;
        for (JsonNode entry : entries) {
            assertThat(entry.isObject()).isTrue();
            String scenario = text(entry, "case"), project = text(entry, "project"), email = text(entry, "email");
            assertThat(scenario).isIn("created", "replayed", "blocked", "employees", "repair-approved", "repair-rejected");
            assertThat(project).isIn("desktop", "mobile-320");
            scenarios.add(scenario + ":" + project);
            if (scenario.startsWith("repair-")) {
                assertThat(entry.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                        "case", "project", "email", "ownerId", "clientId", "equipmentId", "repairId", "budgetId", "trackingCode");
                assertThat(email).isEqualTo(repairEmail(project));
                verifyRepair(entry, project, repairAccounts.get(project), started, finished);
                assertThat(repairClients.add(positiveId(entry, "clientId"))).isTrue();
                assertThat(equipment.add(positiveId(entry, "equipmentId"))).isTrue();
                assertThat(repairs.add(positiveId(entry, "repairId"))).isTrue();
                assertThat(budgets.add(positiveId(entry, "budgetId"))).isTrue();
                assertThat(trackingCodes.add(text(entry, "trackingCode"))).isTrue();
                continue;
            }
            Set<String> fields = scenario.equals("blocked") ? Set.of("case", "project", "email")
                    : scenario.equals("employees") ? Set.of("case", "project", "email", "requiredSetRevision", "acceptances",
                            "idempotencyKey", "employee", "client", "ownerId", "otherOwnerId")
                    : Set.of("case", "project", "email", "requiredSetRevision", "acceptances", "idempotencyKey");
            assertThat(entry.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(email).isEqualTo(scenario + "-" + project + "-" + RUN_ID + "@ordenfix-e2e.test");
            assertThat(emails.add(email)).isTrue();
            if (scenario.equals("blocked")) {
                assertThat(fixture.owner.queryForObject("SELECT count(*) FROM users WHERE email=?", Long.class, email)).isZero();
                assertThat(entry.has("idempotencyKey")).isFalse();
                continue;
            }
            var accepted = readAcceptances(entry.path("acceptances"));
            acts += accepted.size(); documents += accepted.stream().mapToInt(value -> value.documentos().size()).sum();
            var identity = verifyRegistration(email, text(entry, "requiredSetRevision"), text(entry, "idempotencyKey"), accepted, started, finished);
            assertThat(users.add(identity.userId())).isTrue(); assertThat(workshops.add(identity.tallerId())).isTrue();
            assertThat(fixture.owner.queryForList("SELECT id FROM users WHERE taller_id=? AND role='ADMIN'", Long.class,
                    identity.tallerId())).containsExactly(identity.userId());
            if (scenario.equals("employees")) {
                verifyEmployee(entry, project, identity, otherWorkshop);
                assertThat(employees.add(positiveId(entry.path("employee"), "id"))).isTrue();
                assertThat(clients.add(positiveId(entry.path("client"), "id"))).isTrue();
            }
        }
        assertThat(scenarios).containsExactlyInAnyOrder("created:desktop", "created:mobile-320", "replayed:desktop",
                "replayed:mobile-320", "blocked:desktop", "blocked:mobile-320", "employees:desktop", "employees:mobile-320",
                "repair-approved:desktop", "repair-approved:mobile-320", "repair-rejected:desktop", "repair-rejected:mobile-320");
        assertThat(users).hasSize(6); assertThat(workshops).hasSize(6);
        assertThat(employees).hasSize(2).doesNotContainAnyElementsOf(users);
        assertThat(clients).hasSize(2);
        assertThat(repairClients).hasSize(4).doesNotContainAnyElementsOf(clients);
        assertThat(equipment).hasSize(4); assertThat(repairs).hasSize(4);
        assertThat(budgets).hasSize(4); assertThat(trackingCodes).hasSize(4);
        verifyFreeRepairAccounts(repairAccounts, started, finished);
        assertThat(unchangedRows()).isEqualTo(unchangedRows);
        // Login and profile reads must not mutate the pre-existing administrator or workshop.
        // Newly created objects below are checked by their durable values, not by a global DML counter.
        assertThat(baselineAccount()).isEqualTo(otherWorkshop);
        Map<String, Long> after = counts();
        assertThat(after.get("users") - baseline.get("users")).isEqualTo(8);
        assertThat(after.get("clientes") - baseline.get("clientes")).isEqualTo(6);
        for (String table : List.of("equipos", "reparaciones", "presupuestos", "presupuesto_items")) {
            assertThat(after.get(table) - baseline.get(table)).as("repair graph delta for %s", table).isEqualTo(4);
        }
        for (String table : List.of("talleres", "suscripciones", "auth_tokens", "legal_aceptacion_lotes",
                "legal_aceptacion_metadatos", "legal_idempotencia_resultados")) {
            assertThat(after.get(table) - baseline.get(table)).as("durable delta for %s", table).isEqualTo(6);
        }
        assertThat(after.get("legal_aceptaciones") - baseline.get("legal_aceptaciones")).isEqualTo(acts);
        assertThat(after.get("legal_aceptacion_documentos") - baseline.get("legal_aceptacion_documentos")).isEqualTo(documents);
        assertThat(after.get("legal_aceptacion_metadatos_cifrados") - baseline.get("legal_aceptacion_metadatos_cifrados")).isEqualTo(12);
        assertThat(after.get("legal_idempotencia_sin_actos")).isEqualTo(baseline.get("legal_idempotencia_sin_actos"));
    }

    private Map<String, Identity> prepareFreeRepairAccounts() {
        Map<String, Identity> result = new LinkedHashMap<>();
        var writer = application.getBean(LegacyRegistrationAccountWriter.class);
        for (String project : List.of("desktop", "mobile-320")) {
            var identity = writer.create(new RegisterRequestDto("Taller sin cobros", null, "Titular reparaciones",
                    repairEmail(project), PASSWORD));
            // Only the owned fixture's identified rows change; this is not a simulated trial expiry.
            // Both the real writer and these updates use the application's non-superuser credential.
            assertThat(applicationJdbc.update("""
                    UPDATE public.suscripciones SET estado='ACTIVA',fecha_fin_trial=NULL
                     WHERE taller_id=? AND plan='FREE' AND estado='TRIAL'
                    """, identity.tallerId())).isEqualTo(1);
            assertThat(applicationJdbc.update("""
                    UPDATE public.users SET email_verificado=true
                     WHERE id=? AND taller_id=? AND email=? AND role='ADMIN' AND active AND NOT email_verificado
                    """, identity.userId(), identity.tallerId(), repairEmail(project))).isEqualTo(1);
            result.put(project, new Identity(identity.userId(), identity.tallerId()));
        }
        assertThat(result.values()).extracting(Identity::tallerId).doesNotHaveDuplicates();
        return Map.copyOf(result);
    }

    private static String repairEmail(String project) {
        return "repair-free-" + project + "-" + RUN_ID + "@ordenfix-e2e.test";
    }

    private Map<String, List<String>> unchangedRows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String table : UNCHANGED_TABLES) {
            rows.put(table, fixture.owner.queryForList("SELECT to_jsonb(t)::text || ':' || t.xmin::text FROM public."
                    + table + " t ORDER BY 1", String.class));
        }
        return rows;
    }

    private void verifyFreeRepairAccounts(Map<String, Identity> accounts, Instant started, Instant finished) {
        Clock clock = application.getBean(Clock.class);
        // The finite fixture does not freeze application time or claim a month/year rollover test.
        assertThat(YearMonth.from(started.atZone(clock.getZone())))
                .as("run must remain within one application month to assert the two-use counter")
                .isEqualTo(YearMonth.from(finished.atZone(clock.getZone())));
        for (var entry : accounts.entrySet()) {
            var identity = entry.getValue();
            var rows = fixture.owner.queryForList("""
                    SELECT u.username,u.email,u.role,u.active,u.email_verificado,u.token_version,
                           t.nombre,t.activo,s.plan,s.estado,s.fecha_fin_trial,s.consumo_mes,s.reparaciones_mes
                      FROM users u JOIN talleres t ON t.id=u.taller_id JOIN suscripciones s ON s.taller_id=t.id
                     WHERE u.id=? AND t.id=?
                    """, identity.userId(), identity.tallerId());
            assertThat(rows).hasSize(1); var row = rows.getFirst();
            assertThat(row.get("username")).isEqualTo("Titular reparaciones");
            assertThat(row.get("email")).isEqualTo(repairEmail(entry.getKey())); assertThat(row.get("role")).isEqualTo("ADMIN");
            assertThat(row.get("active")).isEqualTo(true); assertThat(row.get("email_verificado")).isEqualTo(true);
            assertThat(((Number) row.get("token_version")).longValue()).isZero();
            assertThat(row.get("nombre")).isEqualTo("Taller sin cobros"); assertThat(row.get("activo")).isEqualTo(true);
            assertThat(row.get("plan")).isEqualTo("FREE"); assertThat(row.get("estado")).isEqualTo("ACTIVA");
            assertThat(row.get("fecha_fin_trial")).isNull();
            assertThat(row.get("consumo_mes")).isEqualTo(YearMonth.from(finished.atZone(clock.getZone())).toString());
            assertThat(((Number) row.get("reparaciones_mes")).intValue()).isEqualTo(2);
            assertThat(fixture.owner.queryForList("SELECT id FROM users WHERE taller_id=?", Long.class, identity.tallerId()))
                    .containsExactly(identity.userId());
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=?", Long.class, identity.userId())).isZero();
            assertThat(fixture.owner.queryForObject("SELECT count(*) FROM reparaciones WHERE taller_id=?", Long.class, identity.tallerId())).isEqualTo(2);
        }
    }

    private void verifyRepair(JsonNode entry, String project, Identity owner, Instant started, Instant finished) {
        assertThat(owner).isNotNull(); assertThat(positiveId(entry, "ownerId")).isEqualTo(owner.userId());
        long clientId = positiveId(entry, "clientId"), equipmentId = positiveId(entry, "equipmentId");
        long repairId = positiveId(entry, "repairId"), budgetId = positiveId(entry, "budgetId");
        boolean approved = text(entry, "case").equals("repair-approved");
        String phone = project.equals("desktop") ? (approved ? "1155000201" : "1155000202")
                : (approved ? "1155000301" : "1155000302");
        var graphs = fixture.owner.queryForList("""
                SELECT r.estado,r.descripcion_problema,r.precio_estimado,r.precio_final,r.codigo_seguimiento,r.numero_orden,
                       r.fecha_conformidad_entrega,r.garantia_dias,r.garantia_inicio,r.garantia_fin,r.created_at,r.updated_at,
                       e.marca,e.modelo,c.nombre,c.apellido,c.telefono,c.email,c.direccion
                  FROM reparaciones r JOIN equipos e ON e.id=r.equipo_id JOIN clientes c ON c.id=e.cliente_id
                 WHERE r.id=? AND e.id=? AND c.id=? AND r.taller_id=? AND e.taller_id=? AND c.taller_id=?
                """, repairId, equipmentId, clientId, owner.tallerId(), owner.tallerId(), owner.tallerId());
        assertThat(graphs).hasSize(1); var repair = graphs.getFirst();
        assertThat(repair.get("estado")).isEqualTo("ENTREGADO"); assertThat(repair.get("descripcion_problema")).isEqualTo("No carga");
        assertThat((BigDecimal) repair.get("precio_estimado")).isEqualByComparingTo("50000"); assertThat(repair.get("precio_final")).isNull();
        assertThat(repair.get("codigo_seguimiento")).isEqualTo(text(entry, "trackingCode"));
        assertThat(text(entry, "trackingCode")).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}");
        assertThat((String) repair.get("numero_orden")).matches("ORD-[0-9]{4}-[0-9]{4}");
        assertThat(repair.get("marca")).isEqualTo("Motorola"); assertThat(repair.get("modelo")).isEqualTo("G31");
        assertThat(repair.get("nombre")).isEqualTo("Cliente recorrido"); assertThat(repair.get("apellido")).isEqualTo("Prueba local");
        assertThat(repair.get("telefono")).isEqualTo(phone); assertThat(repair.get("email")).isNull(); assertThat(repair.get("direccion")).isNull();
        LocalDateTime created = localTimestamp(repair, "created_at"), delivered = localTimestamp(repair, "fecha_conformidad_entrega");
        withinRun(created, started, finished); withinRun(delivered, started, finished);
        assertThat(delivered).isAfterOrEqualTo(created); withinRun(localTimestamp(repair, "updated_at"), started, finished);
        assertThat(((Number) repair.get("garantia_dias")).intValue()).isEqualTo(90);
        LocalDate warrantyStart = ((java.sql.Date) repair.get("garantia_inicio")).toLocalDate();
        assertThat(warrantyStart).isEqualTo(delivered.toLocalDate());
        assertThat(((java.sql.Date) repair.get("garantia_fin")).toLocalDate()).isEqualTo(warrantyStart.plusDays(90));
        var budgets = fixture.owner.queryForList("""
                SELECT estado,tipo,total,validez_dias,valido_hasta,observaciones,fecha_respuesta,created_at,updated_at
                  FROM presupuestos WHERE id=? AND reparacion_id=? AND taller_id=?
                """, budgetId, repairId, owner.tallerId());
        assertThat(budgets).hasSize(1); var budget = budgets.getFirst();
        assertThat(budget.get("estado")).isEqualTo(approved ? "APROBADO" : "RECHAZADO"); assertThat(budget.get("tipo")).isEqualTo("ORIGINAL");
        assertThat((BigDecimal) budget.get("total")).isEqualByComparingTo("50000");
        assertThat(((Number) budget.get("validez_dias")).intValue()).isEqualTo(7); assertThat(budget.get("observaciones")).isNull();
        LocalDateTime response = localTimestamp(budget, "fecha_respuesta"), budgetCreated = localTimestamp(budget, "created_at");
        withinRun(budgetCreated, started, finished); withinRun(response, started, finished);
        withinRun(localTimestamp(budget, "updated_at"), started, finished);
        assertThat(response).isAfterOrEqualTo(budgetCreated).isBeforeOrEqualTo(delivered);
        assertThat(localTimestamp(budget, "valido_hasta")).isAfter(response);
        withinRun(localTimestamp(budget, "valido_hasta").minusDays(7), started, finished);
        var items = fixture.owner.queryForList("SELECT descripcion,cantidad,precio_unitario,tipo_item,calidad FROM presupuesto_items WHERE presupuesto_id=?", budgetId);
        assertThat(items).hasSize(1); var item = items.getFirst();
        assertThat(item.get("descripcion")).isEqualTo("Reparacion de carga"); assertThat(((Number) item.get("cantidad")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("50000");
        assertThat(item.get("tipo_item")).isEqualTo("MANO_DE_OBRA"); assertThat(item.get("calidad")).isNull();
        assertThat(fixture.owner.queryForList("SELECT id FROM presupuestos WHERE reparacion_id=?", Long.class, repairId)).containsExactly(budgetId);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM cobros WHERE reparacion_id=?", Long.class, repairId)).isZero();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM repuestos WHERE reparacion_id=?", Long.class, repairId)).isZero();
    }

    private static LocalDateTime localTimestamp(Map<String, Object> row, String column) {
        assertThat(row.get(column)).as("persisted timestamp %s", column).isInstanceOf(java.sql.Timestamp.class);
        return ((java.sql.Timestamp) row.get(column)).toLocalDateTime();
    }

    private static void withinRun(LocalDateTime value, Instant started, Instant finished) {
        ZoneId zone = ZoneId.systemDefault();
        assertThat(value).isBetween(LocalDateTime.ofInstant(started, zone), LocalDateTime.ofInstant(finished, zone));
    }

    private BaselineAccount baselineAccount() {
        var rows = fixture.owner.queryForList("""
                SELECT u.id,u.taller_id,to_jsonb(u)::text || ':' || u.xmin::text AS user_row,
                       to_jsonb(t)::text || ':' || t.xmin::text AS workshop_row
                  FROM users u JOIN talleres t ON t.id=u.taller_id
                 WHERE u.email='browser-baseline@ordenfix-e2e.test' AND u.role='ADMIN'
                   AND u.active AND u.email_verificado AND t.activo
                """);
        assertThat(rows).hasSize(1); var row = rows.getFirst();
        return new BaselineAccount(((Number) row.get("id")).longValue(), ((Number) row.get("taller_id")).longValue(),
                (String) row.get("user_row"), (String) row.get("workshop_row"));
    }

    private void verifyEmployee(JsonNode entry, String project, Identity owner, BaselineAccount otherWorkshop) {
        assertThat(positiveId(entry, "ownerId")).isEqualTo(owner.userId());
        assertThat(positiveId(entry, "otherOwnerId")).isEqualTo(otherWorkshop.userId());
        assertThat(owner.tallerId()).isNotEqualTo(otherWorkshop.tallerId());
        JsonNode employee = entry.path("employee"), client = entry.path("client");
        assertThat(employee.isObject()).isTrue(); assertThat(client.isObject()).isTrue();
        assertThat(employee.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("id", "email");
        assertThat(client.properties()).extracting(Map.Entry::getKey).containsExactly("id");
        long employeeId = positiveId(employee, "id"), clientId = positiveId(client, "id");
        String employeeEmail = "employee-" + project + "-" + RUN_ID + "@ordenfix-e2e.test";
        assertThat(text(employee, "email")).isEqualTo(employeeEmail);
        var rows = fixture.owner.queryForList("""
                SELECT id,taller_id,username,email,password,role,active,email_verificado,token_version
                  FROM users WHERE email=?
                """, employeeEmail);
        assertThat(rows).hasSize(1); var user = rows.getFirst();
        assertThat(((Number) user.get("id")).longValue()).isEqualTo(employeeId);
        assertThat(((Number) user.get("taller_id")).longValue()).isEqualTo(owner.tallerId());
        assertThat(user.get("username")).isEqualTo("Empleado local");
        assertThat(user.get("email")).isEqualTo(employeeEmail); assertThat(user.get("role")).isEqualTo("USER");
        assertThat(user.get("active")).isEqualTo(false); assertThat(user.get("email_verificado")).isEqualTo(true);
        assertThat(((Number) user.get("token_version")).longValue()).isZero();
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, (String) user.get("password"))).isTrue();
        assertThat(fixture.owner.queryForList("SELECT id FROM users WHERE taller_id=?", Long.class, owner.tallerId()))
                .containsExactlyInAnyOrder(owner.userId(), employeeId);
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=?", Long.class, employeeId)).isZero();
        var clients = fixture.owner.queryForList("SELECT id,taller_id,nombre,apellido,telefono,email,direccion FROM clientes WHERE id=?", clientId);
        assertThat(clients).hasSize(1); var saved = clients.getFirst();
        assertThat(((Number) saved.get("taller_id")).longValue()).isEqualTo(owner.tallerId());
        assertThat(saved.get("nombre")).isEqualTo("Cliente empleado"); assertThat(saved.get("apellido")).isEqualTo("Prueba local");
        assertThat(saved.get("telefono")).isEqualTo(project.equals("desktop") ? "1155000101" : "1155000102");
        assertThat(saved.get("email")).isNull(); assertThat(saved.get("direccion")).isNull();
        assertThat(fixture.owner.queryForList("SELECT id FROM clientes WHERE taller_id=?", Long.class, owner.tallerId())).containsExactly(clientId);
    }

    private static long positiveId(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertThat(value.isIntegralNumber() && value.canConvertToLong()).as("report ID %s", field).isTrue();
        long result = value.longValue(); assertThat(result).isPositive(); return result;
    }

    private Identity verifyRegistration(String email, String revision, String key, List<LegalAcceptanceCommand.Acceptance> accepted,
                                        Instant started, Instant finished) throws Exception {
        var rows = fixture.owner.queryForList("""
                SELECT u.id,u.taller_id,u.username,u.email,u.password,u.role,u.active,u.email_verificado,u.token_version,
                       t.nombre,t.telefono,t.activo,s.plan,s.estado,s.fecha_inicio,s.fecha_fin_trial
                  FROM users u JOIN talleres t ON t.id=u.taller_id JOIN suscripciones s ON s.taller_id=t.id WHERE u.email=?
                """, email);
        assertThat(rows).hasSize(1); var row = rows.getFirst();
        long userId = ((Number) row.get("id")).longValue(), tallerId = ((Number) row.get("taller_id")).longValue();
        assertThat(row.get("username")).isEqualTo("Prueba local"); assertThat(row.get("nombre")).isEqualTo("Laboratorio OrdenFix");
        assertThat(row.get("telefono")).isNull(); assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat(row.get("active")).isEqualTo(true); assertThat(row.get("activo")).isEqualTo(true);
        assertThat(row.get("email_verificado")).isEqualTo(false); assertThat(((Number) row.get("token_version")).longValue()).isZero();
        assertThat(row.get("plan")).isEqualTo("FREE"); assertThat(row.get("estado")).isEqualTo("TRIAL");
        LocalDate start = ((java.sql.Date) row.get("fecha_inicio")).toLocalDate();
        assertThat(((java.sql.Date) row.get("fecha_fin_trial")).toLocalDate()).isEqualTo(start.plusDays(14));
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, (String) row.get("password"))).isTrue();
        var command = LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                (String) row.get("nombre"), null, (String) row.get("username"), email, PASSWORD), revision, accepted);
        byte[] secret = Base64.getDecoder().decode(LegalRegistrationWriterITSupport.HMAC);
        LegalIdempotencyFingerprint expected;
        try { expected = LegalIdempotencyFingerprint.derive(command, key, 1, secret); }
        finally { Arrays.fill(secret, (byte) 0); }
        var ledgers = fixture.owner.queryForList("SELECT * FROM legal_idempotencia_resultados WHERE user_id=?", userId);
        assertThat(ledgers).hasSize(1); var ledger = ledgers.getFirst();
        assertThat(ledger.get("operacion")).isEqualTo("REGISTRO");
        assertThat(ledger.get("route_template")).isEqualTo("/api/auth/register");
        assertThat(ledger.get("scope_hmac")).isEqualTo(expected.scopeHmac());
        assertThat(ledger.get("idempotency_key_hmac")).isEqualTo(expected.idempotencyKeyHmac());
        assertThat(ledger.get("fingerprint_hmac")).isEqualTo(expected.fingerprintHmac());
        assertThat(((Number) ledger.get("hmac_key_version")).intValue()).isEqualTo(1);
        assertThat(((Number) ledger.get("taller_id")).longValue()).isEqualTo(tallerId);
        UUID lot = (UUID) ledger.get("lote_id");
        assertThat(fixture.owner.queryForList("SELECT id FROM legal_aceptacion_lotes WHERE user_id=?", UUID.class, userId)).containsExactly(lot);
        assertThat(fixture.owner.queryForObject("SELECT taller_id=? AND required_set_revision=? AND rol_wire='ADMIN' AND audiencia='ADMIN_TITULAR' AND perfil='REGISTRATION' AND revision_scheme='AGGREGATE_V1' FROM legal_aceptacion_lotes WHERE id=?", Boolean.class, tallerId, revision, lot)).isTrue();
        Instant acceptedAt = fixture.owner.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id=?", OffsetDateTime.class, lot).toInstant();
        assertThat(acceptedAt).isBetween(started, finished);
        assertThat(fixture.owner.queryForList("SELECT requisito_version_id FROM legal_aceptaciones WHERE lote_id=?", UUID.class, lot))
                .containsExactlyInAnyOrderElementsOf(accepted.stream().map(LegalAcceptanceCommand.Acceptance::requisitoVersionId).toList());
        for (var acceptance : accepted) {
            assertThat(fixture.owner.queryForObject("SELECT user_id=? AND taller_id=? AND contexto='REGISTRO' AND tipo_acto=? AND afirmacion_sha256=? FROM legal_aceptaciones WHERE lote_id=? AND requisito_version_id=?", Boolean.class,
                    userId, tallerId, acceptance.tipoActo().name(), acceptance.afirmacionSha256(), lot, acceptance.requisitoVersionId())).isTrue();
            List<String> storedDocuments = fixture.owner.queryForList("SELECT d.documento_version_id::text || ':' || d.sha256 FROM legal_aceptacion_documentos d JOIN legal_aceptaciones a ON a.id=d.aceptacion_id WHERE a.lote_id=? AND a.requisito_version_id=?", String.class, lot, acceptance.requisitoVersionId());
            assertThat(storedDocuments).containsExactlyInAnyOrderElementsOf(acceptance.documentos().stream().map(document -> document.documentoVersionId() + ":" + document.sha256()).toList());
        }
        var metadata = LegalAcceptanceMetadataITSupport.header(fixture.owner, lot);
        assertThat(metadata.capturedAt()).isEqualTo(acceptedAt);
        assertThat(metadata.retainUntil()).isEqualTo(acceptedAt.plus(Duration.ofDays(30)));
        var fields = LegalAcceptanceMetadataITSupport.fields(fixture.owner, lot);
        assertThat(fields).extracting(LegalAcceptanceMetadataITSupport.CipherRow::type).containsExactly("IP", "USER_AGENT");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getFirst())).isEqualTo("127.0.0.1");
        assertThat(LegalAcceptanceMetadataITSupport.decrypt(lot, fields.getLast())).contains("Chrome/");
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=? AND tipo='VERIFICACION_EMAIL' AND usado_en IS NULL AND expira_en>created_at", Long.class, userId)).isEqualTo(1);
        return new Identity(userId, tallerId);
    }

    private void assertPhysicalRoles() {
        String appRole = applicationJdbc.queryForObject("SELECT current_user", String.class);
        var registration = child(application.getBean(LegalRegistrationHttpConfiguration.Capability.class), "registrationContext");
        var requirements = child(application.getBean(LegalPublicRequirementsHttpConfiguration.class), "requirementsContext");
        var documents = child(application.getBean(LegalPublicDocumentHttpConfiguration.class), "readerContext");
        String writerRole = registration.getBean(LegalPrivateRequirementsDataSource.class).withinDeadline(deadline -> registration.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
        String requirementsRole = requirements.getBean(LegalPublicRequirementsDataSource.class).withinDeadline(deadline -> requirements.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
        String documentsRole = documents.getBean(LegalPublicDocumentDataSource.class).withinDeadline(deadline -> documents.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
        assertThat(List.of(appRole, writerRole, requirementsRole, documentsRole)).containsExactly(
                LegalRegistrationHttpITSupport.APP_ROLE, LegalRegistrationWriterITSupport.ROLE, LegalRegistrationHttpITSupport.PUBLIC_ROLE, DOC_ROLE).doesNotHaveDuplicates();
        assertThat(fixture.owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname IN (?,?,?,?) AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolbypassrls AND NOT rolreplication", Long.class,
                appRole, writerRole, requirementsRole, documentsRole)).isEqualTo(4);
        assertThat(fixture.owner.queryForObject("SELECT has_table_privilege(?, 'public.legal_aceptaciones', 'INSERT')", Boolean.class, appRole)).isFalse();
    }
    private static AnnotationConfigApplicationContext child(Object owner, String field) {
        var context = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(owner, field);
        assertThat(context).isNotNull(); assertThat(context.getParent()).isNull(); return context;
    }
    private static Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        COUNTED_TABLES.forEach(table -> result.put(table, fixture.count(table))); return result;
    }
    private static List<LegalAcceptanceCommand.Acceptance> readAcceptances(JsonNode rows) {
        assertThat(rows.isArray()).isTrue(); assertThat(rows).isNotEmpty();
        List<LegalAcceptanceCommand.Acceptance> result = new ArrayList<>();
        for (var row : rows) {
            assertThat(row.path("confirmado").isBoolean() && row.path("confirmado").booleanValue()).isTrue();
            assertThat(row.path("documentos").isArray()).isTrue();
            List<LegalAcceptanceCommand.Document> documents = new ArrayList<>();
            for (var document : row.path("documentos")) documents.add(new LegalAcceptanceCommand.Document(
                    UUID.fromString(text(document, "documentoVersionId")), text(document, "sha256")));
            result.add(new LegalAcceptanceCommand.Acceptance(UUID.fromString(text(row, "requisitoVersionId")),
                    TipoActoLegal.valueOf(text(row, "tipoActo")), text(row, "afirmacionSha256"), documents, true));
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode node, String field) {
        assertThat(node.path(field).isTextual()).as("report field %s", field).isTrue();
        String value = node.path(field).textValue(); assertThat(value).isNotBlank(); return value;
    }
    private static void credentials(Map<String, String> values, String prefix, String role, String password) {
        values.put(prefix + "jdbc-url", POSTGRES.getJdbcUrl()); values.put(prefix + "username", role); values.put(prefix + "password", password);
    }
    private static void extendOnlyTheEphemeralApplicationRole() {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(fixture.owner);
        fixture.owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE " + qualified(BUSINESS_TABLES) + " TO " + LegalRegistrationHttpITSupport.APP_ROLE);
        for (String table : BUSINESS_TABLES) {
            String sequence = fixture.owner.queryForObject("""
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM pg_catalog.pg_attribute
                         WHERE attrelid = pg_catalog.to_regclass(?)
                           AND attname = 'id' AND attnum > 0 AND NOT attisdropped
                    ) THEN pg_catalog.pg_get_serial_sequence(?, 'id') ELSE NULL END
                    """, String.class, "public." + table, "public." + table);
            if (sequence != null) fixture.owner.execute("GRANT USAGE,SELECT ON SEQUENCE " + sequence + " TO " + LegalRegistrationHttpITSupport.APP_ROLE);
        }
    }
    private static void provisionDocumentRole() {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(fixture.owner);
        fixture.owner.execute("CREATE ROLE " + DOC_ROLE + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + DOC_PASSWORD + "'");
        fixture.owner.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + DOC_ROLE);
        fixture.owner.execute("GRANT USAGE ON SCHEMA public TO " + DOC_ROLE);
        fixture.owner.execute("GRANT SELECT ON TABLE " + qualified(LegalPublicDocumentPrivilegeVerifier.READ_TABLES) + " TO " + DOC_ROLE);
        fixture.owner.execute("ALTER ROLE " + DOC_ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path TO pg_catalog,public,pg_temp");
        var reader = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), DOC_ROLE, DOC_PASSWORD));
        new LegalV28AggregateSchemaVerifier(reader, "public").verify();
        new LegalPublicDocumentPrivilegeVerifier(reader, DOC_ROLE, "public").verify();
    }
    private static String qualified(Collection<String> names) { return names.stream().map(value -> "public." + value).collect(Collectors.joining(",")); }

    private static void rememberDescendants(Process process, Map<Long, OwnedProcess> owned) {
        process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), new OwnedProcess(handle, handle.info().startInstant())));
    }
    private static void stopOwnedProcesses(Process process, Map<Long, OwnedProcess> owned) throws Exception {
        rememberDescendants(process, owned);
        if (process.isAlive()) process.destroy();
        owned.values().stream().filter(OwnedProcess::stillOwnedAndAlive).forEach(value -> value.handle().destroy());
        long gracefulUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < gracefulUntil && (process.isAlive() || owned.values().stream().anyMatch(OwnedProcess::stillOwnedAndAlive))) {
            process.waitFor(100, TimeUnit.MILLISECONDS);
            if (!process.isAlive()) TimeUnit.MILLISECONDS.sleep(50);
        }
        if (process.isAlive()) process.destroyForcibly();
        owned.values().stream().filter(OwnedProcess::stillOwnedAndAlive).forEach(value -> value.handle().destroyForcibly());
        process.waitFor(3, TimeUnit.SECONDS);
        long forcedUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < forcedUntil && owned.values().stream().anyMatch(OwnedProcess::stillOwnedAndAlive)) TimeUnit.MILLISECONDS.sleep(50);
        assertThat(process.isAlive()).as("owned npm process must terminate").isFalse();
        assertThat(owned.values()).noneMatch(OwnedProcess::stillOwnedAndAlive);
        assertThat(owned.values()).as("process cleanup incomplete: a live process has no verifiable start identity")
                .noneMatch(OwnedProcess::identityUnavailableWhileAlive);
    }
    private static String tail(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "No Playwright log was produced.";
        try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(Math.max(0, file.length() - 16_384));
            byte[] bytes = new byte[(int) (file.length() - file.getFilePointer())]; file.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 60), lines.length));
        }
    }
    @AfterAll static void removeOnlyTheOwnedPublicationDirectory() throws IOException {
        if (publicationDirectory == null) return;
        try (var paths = Files.walk(publicationDirectory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private record Identity(long userId, long tallerId) { }
    private record BaselineAccount(long userId, long tallerId, String userRow, String workshopRow) { }
    private record OwnedProcess(ProcessHandle handle, java.util.Optional<Instant> started) {
        boolean stillOwnedAndAlive() {
            return handle.isAlive() && started.isPresent() && handle.info().startInstant().equals(started);
        }
        boolean identityUnavailableWhileAlive() {
            return handle.isAlive() && (started.isEmpty() || handle.info().startInstant().isEmpty());
        }
    }
}
