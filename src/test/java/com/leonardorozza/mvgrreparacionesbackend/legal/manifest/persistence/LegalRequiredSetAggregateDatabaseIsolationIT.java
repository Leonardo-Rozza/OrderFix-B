package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class LegalRequiredSetAggregateDatabaseIsolationIT {

    private static final String MATERIALIZER_ROLE =
            "ordenfix_legal_aggregate_isolation_it";
    private static final String MATERIALIZER_PASSWORD =
            "legal-aggregate-isolation-test-only";
    private static final String WEB_ROLE = "ordenfix_legal_web_isolation_it";
    private static final String WEB_PASSWORD = "legal-web-isolation-test-only";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ordenfix_legal_aggregate_isolation")
                    .withUsername("ordenfix")
                    .withPassword("ordenfix");

    private static DataSource ownerDataSource;
    private static DataSource materializerDataSource;
    private static DataSource webDataSource;
    private static JdbcTemplate owner;
    private static JdbcTemplate web;
    private static LegalV28AggregateITSupport.AggregateHarness aggregate;

    @BeforeAll
    static void migrateAndProvisionPhysicallyDistinctRoles() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        ownerDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        LegalRestrictedAggregateRoleFixture.Credentials credentials =
                new LegalRestrictedAggregateRoleFixture(
                        owner,
                        POSTGRES.getJdbcUrl(),
                        MATERIALIZER_ROLE,
                        MATERIALIZER_PASSWORD,
                        POSTGRES.getDriverClassName())
                        .provisionAndVerify();
        materializerDataSource = LegalV28AggregateITSupport.dataSource(credentials);
        aggregate = LegalV28AggregateITSupport.aggregateHarness(
                materializerDataSource,
                MATERIALIZER_ROLE);

        String database = owner.queryForObject(
                "SELECT pg_catalog.current_database()",
                String.class);
        owner.execute("CREATE ROLE " + WEB_ROLE
                + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
                + "NOREPLICATION NOBYPASSRLS PASSWORD '" + WEB_PASSWORD + "'");
        owner.execute("GRANT CONNECT ON DATABASE " + database + " TO " + WEB_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + WEB_ROLE);
        owner.execute("GRANT SELECT ON TABLE public.legal_requisito_agregados, "
                + "public.legal_requisito_agregado_scopes TO " + WEB_ROLE);
        webDataSource = dataSource(WEB_ROLE, WEB_PASSWORD);
        web = new JdbcTemplate(webDataSource);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void aggregateTransactionUsesOnlyItsDedicatedDatasourceAndEffectiveRole() {
        DataSourceTransactionManager manager = (DataSourceTransactionManager)
                aggregate.transaction().getTransactionManager();
        assertThat(manager).isNotNull();
        assertThat(manager.getDataSource()).isSameAs(materializerDataSource);
        assertThat(aggregate.jdbc().getDataSource()).isSameAs(materializerDataSource);
        assertThat(materializerDataSource)
                .isNotSameAs(ownerDataSource)
                .isNotSameAs(webDataSource);
        assertThat(ownerDataSource).isNotSameAs(webDataSource);

        AtomicReference<String> effectiveRole = new AtomicReference<>();
        AtomicInteger materializerPid = new AtomicInteger();
        aggregate.gate().executeMutableShared((status, boundary) -> {
            effectiveRole.set(aggregate.jdbc().queryForObject(
                    "SELECT current_user || ':' || session_user",
                    String.class));
            materializerPid.set(aggregate.jdbc().queryForObject(
                    "SELECT pg_catalog.pg_backend_pid()",
                    Integer.class));
            return null;
        });

        assertThat(effectiveRole).hasValue(MATERIALIZER_ROLE + ":" + MATERIALIZER_ROLE);
        assertThat(materializerPid.get()).isPositive();
        assertThat(owner.queryForObject("SELECT current_user", String.class))
                .isEqualTo(POSTGRES.getUsername());
        assertThat(web.queryForObject("SELECT current_user", String.class))
                .isEqualTo(WEB_ROLE);
        aggregate.schema().verify();
        aggregate.privileges().verify();
    }

    @Test
    void webCredentialCanReadButHasNoDmlOnEitherV28Table() {
        assertThat(web.queryForObject("""
                SELECT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'SELECT')
                   AND pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'SELECT')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'INSERT')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'UPDATE')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'DELETE')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'TRUNCATE')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'INSERT')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'UPDATE')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'DELETE')
                   AND NOT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'TRUNCATE')
                """, Boolean.class)).isTrue();
        assertThat(web.queryForObject(
                "SELECT count(*) FROM legal_requisito_agregados",
                Long.class)).isZero();

        Throwable failure = catchThrowable(() -> web.update("""
                INSERT INTO legal_requisito_agregados
                    (id, perfil, locale, audiencia, revision_scheme,
                     required_set_revision, provenance_fingerprint,
                     scope_count, creado_en)
                VALUES (?, 'AUTHENTICATED_PENDING', 'es-AR', 'USER',
                        'AGGREGATE_V1', ?, ?, 1, statement_timestamp())
                """,
                UUID.randomUUID(),
                LegalV28AggregateITSupport.digest('a'),
                LegalV28AggregateITSupport.digest('b')));
        LegalV28AggregateITSupport.assertSqlState(failure, "42501");
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM legal_requisito_agregados",
                Long.class)).isZero();
        assertThat(aggregate.jdbc().queryForObject("""
                SELECT pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregados',
                           'INSERT')
                   AND pg_catalog.has_table_privilege(
                           current_user,
                           'public.legal_requisito_agregado_scopes',
                           'INSERT')
                """, Boolean.class)).isTrue();
    }

    private static DataSource dataSource(String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
