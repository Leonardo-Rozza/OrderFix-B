package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leonardorozza.mvgrreparacionesbackend.config.CorsConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.JwtFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.filter.PublicEndpointRateLimitFilter;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.RateLimitProperties;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.filters.RemoteIpFilter;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real loopback server and two restricted consumer pools; all custom faults are test-only. */
final class LegalAcceptanceHttpITSupport {
    static final String PATH = "/api/aceptaciones-legales";
    static final String READ_ROLE = "ordenfix_acceptance_http_reader";
    static final String READ_PASSWORD = "synthetic-http-reader-password";
    static final String USER_AGENT = "OrdenFix HTTP integration fixture";
    static final String ORIGIN = "https://ordenfix.test";
    static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String READ = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String HMAC = "ordenfix.legal.idempotency.";
    private static final String META = "ordenfix.legal.account-metadata.";

    private LegalAcceptanceHttpITSupport() { }

    /** The historical reader fixture has another safe DB prefix; reuse its nominal matrices here. */
    static void provisionReader(LegalAcceptanceServiceITSupport fixture) {
        fixture.requireEphemeral(); JdbcTemplate owner = fixture.owner;
        String database = owner.queryForObject("SELECT current_database()", String.class);
        owner.execute("CREATE ROLE " + READ_ROLE + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
                + "NOREPLICATION NOBYPASSRLS PASSWORD '" + READ_PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE \"" + database + "\" TO " + READ_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + READ_ROLE);
        LegalPrivateRequirementsPrivilegeVerifier.READ_TABLES.forEach(table ->
                owner.execute("GRANT SELECT ON public." + table + " TO " + READ_ROLE));
        LegalPrivateRequirementsPrivilegeVerifier.SELECT_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT SELECT(" + String.join(",", columns) + ") ON public." + table + " TO " + READ_ROLE));
        LegalPrivateRequirementsPrivilegeVerifier.INSERT_TABLES.forEach(table ->
                owner.execute("GRANT INSERT ON public." + table + " TO " + READ_ROLE));
        LegalPrivateRequirementsPrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) ->
                owner.execute("GRANT UPDATE(" + String.join(",", columns) + ") ON public." + table + " TO " + READ_ROLE));
        LegalPrivateRequirementsPrivilegeVerifier.PRIVILEGED_FUNCTIONS.forEach(function ->
                owner.execute("GRANT EXECUTE ON FUNCTION public." + function + " TO " + READ_ROLE));
        for (String setting : List.of("search_path TO pg_catalog,public,pg_temp", "session_replication_role TO origin",
                "lo_compat_privileges TO off")) owner.execute("ALTER ROLE " + READ_ROLE + " IN DATABASE \"" + database + "\" SET " + setting);
        var restricted = new JdbcTemplate(new DriverManagerDataSource(fixture.credentials.jdbcUrl(), READ_ROLE, READ_PASSWORD));
        new LegalV29AcceptanceSchemaVerifier(restricted, "public").verify();
        new LegalPrivateRequirementsPrivilegeVerifier(restricted, READ_ROLE, "public").verify();
    }

    static Map<String, Object> properties(LegalAcceptanceServiceITSupport fixture, boolean enabled, String trusted) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server.port", "0"); values.put("server.address", "127.0.0.1");
        values.put("server.shutdown", "immediate"); values.put("server.forward-headers-strategy", "none");
        values.put("server.tomcat.threads.max", "4"); values.put("server.tomcat.threads.min-spare", "1");
        values.put("app.cors.allowed-origins", ORIGIN);
        values.put(PREFIX + "enabled", Boolean.toString(enabled)); values.put(READ + "enabled", "true");
        values.put(READ + "jdbc-url", fixture.credentials.jdbcUrl());
        values.put(READ + "username", READ_ROLE); values.put(READ + "password", READ_PASSWORD);
        values.put(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY, "false");
        values.put(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY, "false");
        if (enabled) {
            values.put(PREFIX + "jdbc-url", fixture.credentials.jdbcUrl());
            values.put(PREFIX + "username", fixture.credentials.username()); values.put(PREFIX + "password", fixture.credentials.password());
            values.put(HMAC + "keyring.1", LegalAcceptanceServiceITSupport.HMAC_SECRET);
            values.put(HMAC + "active-write-version", "1");
            values.put(META + "keyring.7", LegalAcceptanceServiceITSupport.AES_SECRET);
            values.put(META + "active-write-version", "7"); values.put(META + "retention", "P30D");
            values.put(META + "trusted-proxy-cidrs", trusted);
            values.put("security.jwt.secret", "j".repeat(32));
            values.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", Base64.getEncoder().encodeToString("d".repeat(32).getBytes(StandardCharsets.US_ASCII)));
        }
        return values;
    }

    static Running open(LegalAcceptanceServiceITSupport fixture, boolean enabled, String trusted,
                        Fault fault, Class<?>... extraConfiguration) {
        var context = new AnnotationConfigServletWebServerApplicationContext();
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("acceptance-http-it", properties(fixture, enabled, trusted)));
        context.setEnvironment(environment);
        var clock = new AtomicLong();
        context.registerBean("httpBodyDeadlineClock", AtomicLong.class, () -> clock);
        context.register(WebConfiguration.class, LegalAcceptanceHttpConfiguration.class,
                LegalAcceptancePeerConfiguration.class, LegalAcceptanceController.class, LegalAcceptanceExceptionHandler.class,
                LegalPrivateRequirementsHttpConfiguration.class, LegalAcceptanceHistoryController.class,
                LegalAcceptanceHistoryExceptionHandler.class, LegalPrivateRequirementsAuthenticationEntryPoint.class,
                SecurityConfig.class, CorsConfig.class, JwtFilter.class, PublicEndpointRateLimitFilter.class,
                LegalPublicDocumentRequestMatcher.class, LegalPublicRequirementsRequestMatcher.class,
                RateLimitProperties.class, GlobalExceptionHandler.class);
        if (extraConfiguration.length > 0) context.register(extraConfiguration);
        try {
            context.refresh();
            var readContext = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                    context.getBean(LegalPrivateRequirementsHttpConfiguration.class), "requirementsContext");
            AnnotationConfigApplicationContext acceptance = enabled ? (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(
                    context.getBean(LegalAcceptanceHttpConfiguration.class), "acceptanceContext") : null;
            HikariDataSource pool = enabled ? acceptance.getBean(HikariDataSource.class) : null;
            LegalJdbcMetricsSupport metrics = enabled ? LegalJdbcMetricsSupport.instrument(pool, Duration.ZERO) : null;
            var probe = new Probe(fault);
            if (enabled) {
                var bounded = acceptance.getBean(LegalPrivateRequirementsDataSource.class);
                ReflectionTestUtils.setField(bounded, "pool", instrument(metrics.dataSource(), probe));
                // Deterministic cooperative body deadline; no assertion about a network/body-read SLA.
                ReflectionTestUtils.setField(bounded, "clock", (java.util.function.LongSupplier) () -> System.nanoTime() + clock.get());
            }
            return new Running(context, acceptance, readContext, pool, metrics, probe, clock);
        } catch (RuntimeException | Error failure) {
            try { context.close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static ObjectNode body(LegalAcceptanceCommand command) {
        ObjectNode body = JSON.createObjectNode(); body.put("requiredSetRevision", command.requiredSetRevision());
        var acceptances = body.putArray("aceptacionesLegales");
        for (var acceptance : command.acceptances()) {
            var act = acceptances.addObject(); act.put("requisitoVersionId", acceptance.requisitoVersionId().toString());
            act.put("tipoActo", acceptance.tipoActo().name()); act.put("afirmacionSha256", acceptance.afirmacionSha256());
            act.put("confirmado", acceptance.confirmado()); var documents = act.putArray("documentos");
            for (var document : acceptance.documentos()) documents.addObject()
                    .put("documentoVersionId", document.documentoVersionId().toString()).put("sha256", document.sha256());
        }
        return body;
    }

    static Map<String, List<String>> headers(String... pairs) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.computeIfAbsent(pairs[index], ignored -> new ArrayList<>()).add(pairs[index + 1]);
        return values;
    }

    static final class Running implements AutoCloseable {
        final AnnotationConfigServletWebServerApplicationContext context;
        final AnnotationConfigApplicationContext acceptance;
        final AnnotationConfigApplicationContext readContext;
        final HikariDataSource pool;
        final LegalJdbcMetricsSupport metrics;
        final Probe probe;
        final AtomicLong clock;
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();

        Running(AnnotationConfigServletWebServerApplicationContext context, AnnotationConfigApplicationContext acceptance,
                AnnotationConfigApplicationContext readContext, HikariDataSource pool, LegalJdbcMetricsSupport metrics,
                Probe probe, AtomicLong clock) {
            this.context = context; this.acceptance = acceptance; this.readContext = readContext;
            this.pool = pool; this.metrics = metrics; this.probe = probe; this.clock = clock;
        }

        String authorize(LegalActorSnapshot actor) {
            User user = User.builder().id(actor.userId()).email("http-actor-" + actor.userId() + "@ordenfix.test")
                    .password("synthetic-principal-hash").active(true).role(actor.role()).tokenVersion(actor.tokenVersion())
                    .taller(Taller.builder().id(actor.tallerId()).activo(true).build()).build();
            return authorize(new AuthenticatedUserPrincipal(user));
        }

        String unsupportedRole(LegalActorSnapshot actor) {
            var principal = mock(AuthenticatedUserPrincipal.class);
            when(principal.getUsername()).thenReturn("unsupported-" + actor.userId() + "@ordenfix.test");
            when(principal.getTallerId()).thenReturn(actor.tallerId());
            doReturn(List.of(new SimpleGrantedAuthority("ROLE_AUDITOR"))).when(principal).getAuthorities();
            return authorize(principal);
        }

        private String authorize(AuthenticatedUserPrincipal principal) {
            String token = "synthetic-http-token-" + UUID.randomUUID(); var decoded = mock(DecodedJWT.class);
            String username = principal.getUsername();
            when(decoded.getSubject()).thenReturn(username);
            doReturn(decoded).when(context.getBean(JwtUtils.class)).verifyToken(token);
            when(context.getBean(UserDetailsServiceImpl.class).loadUserByUsername(username)).thenReturn(principal);
            when(context.getBean(JwtUtils.class).validateToken(decoded, principal)).thenReturn(true);
            return token;
        }

        HttpResponse<byte[]> post(String token, String key, ObjectNode body) throws Exception {
            return send("POST", PATH, token, JSON.writeValueAsBytes(body),
                    headers("Content-Type", "application/json", "Idempotency-Key", key, "User-Agent", USER_AGENT));
        }

        HttpResponse<byte[]> send(String method, String path, String token, byte[] body, Map<String, List<String>> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(20));
            if (token != null) request.header("Authorization", "Bearer " + token);
            headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        @Override public void close() {
            HikariDataSource readerPool = readContext.getBean(HikariDataSource.class);
            try { client.close(); } finally { context.close(); }
            assertThat(readContext.isActive()).isFalse(); assertThat(readContext.getParent()).isNull();
            assertThat(readerPool.isClosed()).isTrue();
            if (acceptance != null) { assertThat(acceptance.isActive()).isFalse(); assertThat(pool.isClosed()).isTrue(); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    static class WebConfiguration {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JwtUtils jwtUtils() {
            var jwt = mock(JwtUtils.class); when(jwt.verifyToken(anyString())).thenThrow(new JWTVerificationException("Synthetic invalid token")); return jwt;
        }
        @Bean UserDetailsServiceImpl users() { return mock(UserDetailsServiceImpl.class); }
        @Bean FilterRegistrationBean<Filter> bodyDeadlineProbe(AtomicLong deadlineClock) {
            var registration = new FilterRegistrationBean<Filter>(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    if (!"true".equals(request.getHeader("X-Fixture-Expire-Body"))) { chain.doFilter(request, response); return; }
                    chain.doFilter(new HttpServletRequestWrapper(request) {
                        @Override public ServletInputStream getInputStream() throws IOException {
                            var stream = super.getInputStream();
                            return new ServletInputStream() {
                                @Override public int read() throws IOException { int value = stream.read(); deadlineClock.set(Duration.ofSeconds(15).toNanos()); return value; }
                                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                    int value = stream.read(bytes, offset, length); deadlineClock.set(Duration.ofSeconds(15).toNanos()); return value;
                                }
                                @Override public boolean isFinished() { return stream.isFinished(); }
                                @Override public boolean isReady() { return stream.isReady(); }
                                @Override public void setReadListener(ReadListener listener) { stream.setReadListener(listener); }
                            };
                        }
                    }, response);
                }
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE); return registration;
        }
    }

    @TestConfiguration(proxyBeanMethods = false) static class HostileValveConfiguration {
        @Bean TomcatContextCustomizer hostileValve() { return context -> context.getPipeline().addValve(new RemoteIpValve()); }
    }
    @TestConfiguration(proxyBeanMethods = false) static class HostileForwardedFilterConfiguration {
        @Bean FilterRegistrationBean<ForwardedHeaderFilter> hostileFilter() {
            return new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class HostileRemoteIpFilterConfiguration {
        @Bean FilterRegistrationBean<RemoteIpFilter> hostileRemoteFilter() {
            return new FilterRegistrationBean<>(new RemoteIpFilter() { });
        }
    }

    enum Fault { NONE, SQL_METADATA, COMMIT_ACK }
    static final class Probe { final Fault fault; final AtomicBoolean injected = new AtomicBoolean(); Probe(Fault fault) { this.fault = fault; } }

    private static DataSource instrument(DataSource source, Probe probe) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    Object result = invoke(delegate, method, args);
                    if (method.getName().equals("commit") && probe.fault == Fault.COMMIT_ACK && probe.injected.compareAndSet(false, true))
                        throw new SQLException("Synthetic lost HTTP commit acknowledgement", "08006");
                    if (result instanceof Statement statement) {
                        String prepared = args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
                        Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (statementProxy, operation, arguments) -> {
                            if (operation.getDeclaringClass() == Object.class) return objectMethod(statementProxy, operation, arguments);
                            String sql = prepared != null ? prepared : arguments != null && arguments.length > 0 && arguments[0] instanceof String text ? text : "";
                            if (operation.getName().startsWith("execute") && probe.fault == Fault.SQL_METADATA
                                    && sql.stripLeading().toLowerCase(java.util.Locale.ROOT).matches("(?s)insert\\s+into\\s+(?:public\\.)?legal_aceptacion_metadatos(?:\\s|\\().*")
                                    && probe.injected.compareAndSet(false, true)) {
                                try (Statement broken = delegate.createStatement()) { broken.execute("SELECT 1/0"); }
                            }
                            return invoke(statement, operation, arguments);
                        });
                    }
                    return result;
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLFeatureNotSupportedException("Fixed HTTP fixture role"); }
        };
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) { case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "AcceptanceHttpJdbcProbe"; default -> throw new IllegalStateException("Unexpected Object method"); };
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
