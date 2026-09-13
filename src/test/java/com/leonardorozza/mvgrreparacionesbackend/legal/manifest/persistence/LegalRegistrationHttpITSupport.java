package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.controller.AuthController;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.*;
import com.leonardorozza.mvgrreparacionesbackend.service.email.EmailSender;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.*;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Mock servlet transport, actual controller/bridge and independently committed PostgreSQL services.
 * No Tomcat, filter-chain, socket-peer or network timing claim is made by this fixture. */
final class LegalRegistrationHttpITSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String URL = "/api/auth/register";
    static final String APP_ROLE = "registration_http_app";
    static final String PUBLIC_ROLE = "registration_http_public";
    static final String APP_PASSWORD = "registration-http-application-fixture";
    static final String PUBLIC_PASSWORD = "registration-http-public-fixture";
    static final String JWT_SECRET = "registration-http-synthetic-jwt-secret-at-least-32-bytes";
    static final String USER_AGENT = "OrdenFix registration HTTP fixture";
    final PostgreSQLContainer postgres;
    final LegalRegistrationServiceITSupport fixture;
    final JdbcTemplate owner;

    LegalRegistrationHttpITSupport(PostgreSQLContainer postgres) {
        this.postgres = postgres;
        this.fixture = new LegalRegistrationServiceITSupport(postgres);
        owner = fixture.owner;
        // The SQL-only writer fixture keeps its V29 default; HTTP/JPA needs the current entity schema.
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        LegalManifestPersistenceITSupport.migrateLatest(postgres);
        provisionApplicationRole();
        provisionPublicReaderRole();
    }
    void reset(Path directory, Class<?> anchor) throws Exception { fixture.reset(directory, anchor); }
    LegalRegistrationWriterITSupport.Request request() throws Exception { return fixture.request(); }
    Harness harness() { return new Harness(true, false, false); }
    Harness harness(boolean consent, boolean enforcement) { return new Harness(consent, enforcement, false); }
    Harness productionHarness() { return new Harness(true, true, true); }
    Map<String, List<String>> rows() { return fixture.rows(); }
    long count(String table) { return Objects.requireNonNull(owner.queryForObject("SELECT count(*) FROM public." + table, Long.class)); }
    long userId() { return Objects.requireNonNull(owner.queryForObject("SELECT id FROM users", Long.class)); }
    long tallerId() { return Objects.requireNonNull(owner.queryForObject("SELECT id FROM talleres", Long.class)); }

    static ObjectNode body(LegalRegistrationWriterITSupport.Request request) {
        var registration = request.command().registration();
        ObjectNode body = JSON.createObjectNode().put("nombreTaller", registration.nombreTaller())
                .put("telefonoTaller", registration.telefonoTaller()).put("nombreAdmin", registration.nombreAdmin())
                .put("email", registration.email()).put("password", registration.password())
                .put("requiredSetRevision", request.command().requiredSetRevision());
        var acceptances = body.putArray("aceptacionesLegales");
        for (var acceptance : request.command().acceptances()) {
            var value = acceptances.addObject().put("requisitoVersionId", acceptance.requisitoVersionId().toString())
                    .put("tipoActo", acceptance.tipoActo().name()).put("afirmacionSha256", acceptance.afirmacionSha256())
                    .put("confirmado", acceptance.confirmado());
            var documents = value.putArray("documentos");
            for (var document : acceptance.documentos()) {
                documents.addObject().put("documentoVersionId", document.documentoVersionId().toString())
                        .put("sha256", document.sha256());
            }
        }
        return body;
    }
    static ObjectNode legacyBody(LegalRegistrationWriterITSupport.Request request) {
        ObjectNode body = body(request); body.remove(List.of("requiredSetRevision", "aceptacionesLegales")); return body;
    }
    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    static void assertStatus(MvcResult result, int expected) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("ETag")).isNull();
    }
    static void assertError(MvcResult result, int status, String code) throws Exception {
        assertStatus(result, status);
        JsonNode value = json(result);
        assertThat(value.path("status").asInt()).isEqualTo(status);
        assertThat(value.path("path").asText()).isEqualTo(URL);
        if (code == null) assertThat(value.path("code").isMissingNode() || value.path("code").isNull()).isTrue();
        else assertThat(value.path("code").asText()).isEqualTo(code);
        assertThat(value.toString()).doesNotContain("SELECT", "INSERT", "org.postgresql", APP_PASSWORD,
                "password", "completion", "persistence", "confirmedReceipt", "encodedPassword");
    }

    final class Harness implements AutoCloseable {
        final LegalRegistrationServiceITSupport.Harness writer = fixture.harness();
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final SessionProbe sessions = new SessionProbe(owner);
        AnnotationConfigApplicationContext publicContext;
        final MockMvc mvc;
        Harness(boolean consent, boolean enforcement, boolean production) {
            try {
                Map<String, String> properties = new LinkedHashMap<>();
                properties.put("spring.datasource.url", postgres.getJdbcUrl());
                properties.put("spring.datasource.username", APP_ROLE);
                properties.put("spring.datasource.password", APP_PASSWORD);
                properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
                properties.put("spring.datasource.hikari.maximum-pool-size", "2");
                properties.put("spring.datasource.hikari.minimum-idle", "0");
                properties.put("spring.datasource.hikari.connection-timeout", "3000");
                properties.put("spring.datasource.hikari.initialization-fail-timeout", "-1");
                properties.put("spring.jpa.hibernate.ddl-auto", "validate");
                properties.put("spring.jpa.open-in-view", "false");
                properties.put("server.forward-headers-strategy", "none");
                properties.put("security.jwt.secret", JWT_SECRET);
                properties.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(
                        "dddddddddddddddddddddddddddddddd".getBytes(StandardCharsets.US_ASCII)));
                properties.put(LegalRegistrationHttpConfiguration.CONSENT_PROPERTY, Boolean.toString(consent));
                properties.put(LegalRegistrationHttpConfiguration.ENFORCEMENT_PROPERTY, Boolean.toString(enforcement));
                properties.put("ordenfix.legal.public-documents.enabled", "true");
                properties.put("ordenfix.legal.public-requirements.enabled", "true");
                var credentials = fixture.writerFixture.credentials;
                properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", credentials.jdbcUrl());
                properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "username", credentials.username());
                properties.put(LegalRegistrationDatabaseConfiguration.PROPERTY_PREFIX + "password", credentials.password());
                properties.put(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "keyring.1", LegalRegistrationWriterITSupport.HMAC);
                properties.put(LegalAcceptanceKeyConfiguration.IDEMPOTENCY_PREFIX + "active-write-version", "1");
                properties.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "keyring.7", LegalRegistrationWriterITSupport.AES);
                properties.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "active-write-version", "7");
                properties.put(LegalAcceptanceKeyConfiguration.METADATA_PREFIX + "retention", "P30D");
                TestPropertyValues.of(properties).applyTo(context);
                context.getBeanFactory().registerSingleton("registrationHttpSessionProbe", sessions);
                context.register(JpaConfiguration.class, LegacyRegistrationAccountWriter.class, RegistroService.class,
                        AccountVerificationTokenIssuer.class, AccountVerificationNotifier.class);
                context.register(production ? LegalRegistrationHttpConfiguration.class : LegalRegistrationSessionIssuerConfiguration.class);
                context.refresh();
                assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
                var source = context.getBean(DataSource.class);
                assertThat(context.getBean(JpaTransactionManager.class).getDataSource()).isSameAs(source);
                assertThat(((EntityManagerFactoryInfo) context.getBean(EntityManagerFactory.class)).getDataSource()).isSameAs(source);
                assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(source);
                var bridge = production ? context.getBean(LegalRegistrationHttpBridge.class)
                        : new LegalRegistrationHttpBridge(context.getBean(RegistroService.class), context.getBean(Validator.class),
                        consent, enforcement, writer::service, this::publicReader, () -> new LegalRequestMetadataResolver(List.of()),
                        () -> context.getBean(LegalRegistrationSessionIssuer.class), context.getBean(AccountVerificationNotifier.class));
                // Only unrelated AuthController collaborators are doubles; no invoked registration,
                // session, password, signature, token persistence or legal reader is mocked.
                mvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(AuthService.class), bridge, mock(CuentaService.class)))
                        .setControllerAdvice(new LegalRegistrationExceptionHandler(), new GlobalExceptionHandler()).build();
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        MvcResult post(ObjectNode body, String... keys) throws Exception { return post(body.toString(), keys); }
        MvcResult post(String body, String... keys) throws Exception {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(URL).contentType("application/json").characterEncoding(StandardCharsets.UTF_8)
                    .content(body).header("User-Agent", USER_AGENT)
                    .with(servlet -> { servlet.setRemoteAddr("192.0.2.31"); return servlet; });
            if (keys.length > 0) request.header("Idempotency-Key", (Object[]) keys);
            return mvc.perform(request).andReturn();
        }
        LegalPublicRequirementsReadService publicReader() {
            if (publicContext == null) {
                var opened = new AnnotationConfigApplicationContext();
                try {
                    TestPropertyValues.of(Map.of(LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true",
                            LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "jdbc-url", postgres.getJdbcUrl(),
                            LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "username", PUBLIC_ROLE,
                            LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + "password", PUBLIC_PASSWORD)).applyTo(opened);
                    opened.register(LegalPublicRequirementsDatabaseConfiguration.class); opened.refresh(); publicContext = opened;
                } catch (RuntimeException | Error failure) { opened.close(); throw failure; }
            }
            return publicContext.getBean(LegalPublicRequirementsReadService.class);
        }
        ObservingJwt jwt() { return context.getBean(ObservingJwt.class); }
        void assertReleased() {
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
            var resources = context.getBean(LegalRegistrationSessionResources.class);
            var privatePool = (HikariDataSource) ReflectionTestUtils.getField(resources, "pool");
            assertThat(Objects.requireNonNull(privatePool).getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(writer.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        }
        @Override public void close() {
            try { if (publicContext != null) publicContext.close(); }
            finally { try { context.close(); } finally { writer.close(); } }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class JpaConfiguration {
        @Bean static PersistenceManagedTypes registrationManagedTypes() {
            return PersistenceManagedTypes.of(User.class.getName(), Taller.class.getName(), Suscripcion.class.getName(), AuthToken.class.getName());
        }
        @Bean UserRepository registrationUsers(EntityManagerFactory factory, SessionProbe probe) {
            var real = repository(factory, UserRepository.class);
            return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("findSessionByIdAndTallerId")
                                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                            probe.sessionReads.incrementAndGet();
                        }
                        try { return method.invoke(real, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Bean TallerRepository registrationWorkshops(EntityManagerFactory factory) { return repository(factory, TallerRepository.class); }
        @Bean SuscripcionRepository registrationSubscriptions(EntityManagerFactory factory) { return repository(factory, SuscripcionRepository.class); }
        @Bean AuthTokenRepository registrationTokens(EntityManagerFactory factory) { return repository(factory, AuthTokenRepository.class); }
        private static <T> T repository(EntityManagerFactory factory, Class<T> type) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(type);
        }
        @Bean PasswordEncoder registrationEncoder() { return new BCryptPasswordEncoder(); }
        @Bean ObservingJwt registrationJwt(SessionProbe probe) { return new ObservingJwt(probe); }
        @Bean AccountSessionPolicy registrationSessionPolicy(UserRepository users, PasswordEncoder encoder, ObservingJwt jwt) {
            return new AccountSessionPolicy(users, encoder, jwt);
        }
        @Bean Clock registrationClock() { return Clock.systemUTC(); }
        @Bean LocalValidatorFactoryBean registrationValidator() { return new LocalValidatorFactoryBean(); }
        @Bean EmailSender registrationEmailSender(SessionProbe probe) { return probe::email; }
    }
    static final class ObservingJwt extends JwtUtils {
        final SessionProbe probe;
        ObservingJwt(SessionProbe probe) { super(JWT_SECRET, 3_600_000, "ordenfix-registration-http-it", "ordenfix-api-http-it", 30); this.probe = probe; }
        @Override public String generateToken(UserDetails user, Long workshop) {
            probe.signatures.incrementAndGet();
            String signed = super.generateToken(user, workshop);
            if (probe.failSignature) throw new IllegalStateException("Synthetic failure after real JWT signing");
            return signed;
        }
    }
    static final class SessionProbe {
        final JdbcTemplate owner;
        final AtomicInteger signatures = new AtomicInteger();
        final AtomicInteger sessionReads = new AtomicInteger();
        final List<String> recipients = new ArrayList<>();
        boolean failSignature;
        SessionProbe(JdbcTemplate owner) { this.owner = owner; }
        void email(String recipient, String subject, String html) {
            // AssertionError is deliberately not swallowed by the notifier's RuntimeException catch.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, recipient)).isEqualTo(1L);
            assertThat(owner.queryForObject("SELECT count(*) FROM auth_tokens a JOIN users u ON u.id=a.user_id WHERE u.email=? AND a.tipo='VERIFICACION_EMAIL'", Long.class, recipient)).isEqualTo(1L);
            assertThat(subject).isEqualTo("Confirmá tu email de OrdenFix");
            assertThat(html).contains("/verificar-email?token=");
            recipients.add(recipient);
        }
    }

    private void provisionApplicationRole() {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        owner.execute("CREATE ROLE " + APP_ROLE + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO " + APP_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users,talleres,suscripciones,auth_tokens TO " + APP_ROLE);
        owner.execute("GRANT USAGE, SELECT ON SEQUENCE users_id_seq,talleres_id_seq,suscripciones_id_seq,auth_tokens_id_seq TO " + APP_ROLE);
    }
    /** The older public-role fixture deliberately accepts a different database prefix. Reuse its
     * exact nominal grants here under the registration fixture's own ephemeral-database guard. */
    private void provisionPublicReaderRole() {
        LegalRestrictedRegistrationRoleFixture.requireSafeEphemeralDatabase(owner);
        owner.execute("CREATE ROLE " + PUBLIC_ROLE + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + PUBLIC_PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO " + PUBLIC_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + PUBLIC_ROLE);
        owner.execute("GRANT SELECT ON TABLE " + qualified(LegalPublicRequirementsPrivilegeVerifier.READ_TABLES) + " TO " + PUBLIC_ROLE);
        owner.execute("GRANT INSERT ON TABLE " + qualified(LegalPublicRequirementsPrivilegeVerifier.INSERT_TABLES) + " TO " + PUBLIC_ROLE);
        LegalPublicRequirementsPrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) -> owner.execute(
                "GRANT UPDATE (" + String.join(",", columns) + ") ON TABLE public." + table + " TO " + PUBLIC_ROLE));
        owner.execute("GRANT EXECUTE ON FUNCTION " + qualified(LegalPublicRequirementsPrivilegeVerifier.PRIVILEGED_FUNCTIONS) + " TO " + PUBLIC_ROLE);
        owner.execute("ALTER ROLE " + PUBLIC_ROLE + " IN DATABASE " + postgres.getDatabaseName() + " SET search_path TO pg_catalog,public,pg_temp");
        var actual = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), PUBLIC_ROLE, PUBLIC_PASSWORD));
        new LegalV28AggregateSchemaVerifier(actual, "public").verify();
        new LegalPublicRequirementsPrivilegeVerifier(actual, PUBLIC_ROLE, "public").verify();
    }
    private static String qualified(Collection<String> names) { return names.stream().map(name -> "public." + name).collect(Collectors.joining(",")); }
}
