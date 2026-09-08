package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.util.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Factory/copy tests only; Boot/JPA and physical PostgreSQL routing have their own nominal IT. */
class LegalRegistrationSessionPoolFactoryTest {
    private static final String INVALID = "La configuración de sesión de registro no es compatible";
    private static final String URL = "jdbc:postgresql://localhost:5432/registration_fixture";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createsABoundedPrivatePoolWithoutBorrowingOrMutatingTheHistoricalPool(boolean autoCommit) {
        try (HikariDataSource original = source()) {
            original.setAutoCommit(autoCommit);
            original.setIsolateInternalQueries(false);
            original.setReadOnly(true);
            original.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
            original.setCatalog("fixture_catalog");
            original.setSchema("fixture_schema");
            original.setConnectionTimeout(7_000);
            original.setValidationTimeout(3_000);
            original.setMinimumIdle(3);
            original.setMaximumPoolSize(7);
            original.addDataSourceProperty("ApplicationName", "original-application");
            original.addDataSourceProperty("currentSchema", "application_schema");
            Properties before = copy(original.getDataSourceProperties());
            int globalTimeout = DriverManager.getLoginTimeout();
            assertThat(original.getHikariPoolMXBean()).isNull();
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                assertThat(dedicated).isNotSameAs(original);
                assertThat(dedicated.getMinimumIdle()).isZero();
                assertThat(dedicated.getMaximumPoolSize()).isEqualTo(2);
                assertThat(dedicated.getConnectionTimeout()).isEqualTo(1_000);
                assertThat(dedicated.getValidationTimeout()).isEqualTo(1_000);
                assertThat(dedicated.getInitializationFailTimeout()).isEqualTo(-1);
                assertThat(dedicated.isAutoCommit()).isEqualTo(autoCommit);
                assertThat(dedicated.isIsolateInternalQueries()).isEqualTo(!autoCommit);
                assertThat(dedicated.getConnectionInitSql()).isEqualTo(autoCommit ? null : "SELECT 1");
                assertThat(dedicated.isReadOnly()).isTrue();
                assertThat(dedicated.getTransactionIsolation()).isEqualTo("TRANSACTION_REPEATABLE_READ");
                assertThat(dedicated.getCatalog()).isEqualTo("fixture_catalog");
                assertThat(dedicated.getSchema()).isEqualTo("fixture_schema");
                assertThat(dedicated.getHikariPoolMXBean().getTotalConnections()).isZero();
                assertThat(dedicated.getJdbcUrl()).isNull();
                assertThat(dedicated.getUsername()).isNull();
                assertThat(dedicated.getPassword()).isNull();
                assertThat(dedicated.getDataSourceProperties()).isEmpty();
                assertThat(dedicated.getThreadFactory()).isNull();
                assertThat(dedicated.getScheduledExecutor()).isNull();
                assertThat(dedicated.isRegisterMbeans()).isFalse();
                assertThat(properties(dedicated)).containsEntry("ApplicationName", "original-application")
                        .containsEntry("currentSchema", "application_schema");
                assertTimeouts(properties(dedicated));
            }
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalTimeout);
            assertThat(original.isClosed()).isFalse();
            assertThat(original.getHikariPoolMXBean()).isNull();
            assertThat(original.getJdbcUrl()).isEqualTo(URL);
            assertThat(original.getDataSourceProperties()).isEqualTo(before);
            assertThat(original.isAutoCommit()).isEqualTo(autoCommit);
            assertThat(original.isIsolateInternalQueries()).isFalse();
            assertThat(original.getConnectionInitSql()).isNull();
            assertThat(original.getConnectionTimeout()).isEqualTo(7_000);
            assertThat(original.getValidationTimeout()).isEqualTo(3_000);
            assertThat(original.getMinimumIdle()).isEqualTo(3);
            assertThat(original.getMaximumPoolSize()).isEqualTo(7);
        }
    }

    @ParameterizedTest @MethodSource("credentialCases")
    void reproducesHikariCredentialOverloadsThenUrlPrecedenceWithoutNormalization(
            String hikariUser, String hikariPassword, String propertyUser, String propertyPassword,
            String query, String expectedUser, String expectedPassword) {
        try (HikariDataSource original = source()) {
            original.setCredentials(Credentials.of(hikariUser, hikariPassword));
            if (propertyUser != null) original.addDataSourceProperty("user", propertyUser);
            if (propertyPassword != null) original.addDataSourceProperty("password", propertyPassword);
            original.setJdbcUrl(URL + query);
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                assertThat(properties(dedicated)).containsEntry("user", expectedUser).containsEntry("password", expectedPassword);
                assertThat(url(dedicated)).isEqualTo(URL);
            }
        }
    }

    static Stream<Arguments> credentialCases() {
        return Stream.of(
                Arguments.of("hikari-user", "hikari-password", "property-user", "property-password", "", "hikari-user", "hikari-password"),
                Arguments.of("hikari-user", null, "property-user", "property-password", "", "hikari-user", "property-password"),
                Arguments.of(null, "hikari-password", "property-user", "property-password", "", "property-user", "property-password"),
                Arguments.of(null, "hikari-password", "property-user", null, "", "property-user", "hikari-password"),
                Arguments.of(null, null, "property-user", "property-password", "", "property-user", "property-password"),
                Arguments.of("hikari-user", "hikari-password", "property-user", "property-password", "?user=url-user&password=url-password", "url-user", "url-password"),
                Arguments.of("hikari-user", "hikari-password", null, null, "?user=url-user", "url-user", "hikari-password"),
                Arguments.of(null, null, null, null, "?user=url-user&password=url-password", "url-user", "url-password"),
                Arguments.of("", "", null, null, "?user=url-user", "url-user", ""),
                Arguments.of(" spaced user ", "  password \t", "property-user", "property-password", "", " spaced user ", "  password \t"),
                Arguments.of("hikari-user", "hikari-password", null, null,
                        "?user=discarded&password=discarded&&user=%20usuario%2B%F0%9F%94%A7%20&password=%20clave%26%3D%2B%20",
                        " usuario+🔧 ", " clave&=+ ")
        );
    }

    @Test
    void queryValuesAndLiteralKeysMatchTheRuntimePgjdbcParserAndTimeoutsCannotEscape() {
        try (HikariDataSource original = source()) {
            String query = "?ApplicationName=url+application&sslmode=require&currentSchema=one%2Ctwo"
                    + "&user=first&user=url-user&password=secret%2Bpassword&connectTimeout=0&loginTimeout=99"
                    + "&socketTimeout=0&queryTimeout=0&cancelSignalTimeout=0&socket%54imeout=0&bare";
            original.setJdbcUrl(URL + query);
            original.addDataSourceProperty("ApplicationName", "property-application");
            original.addDataSourceProperty("sslmode", "disable");
            Properties connectionArguments = copy(original.getDataSourceProperties());
            connectionArguments.setProperty("user", original.getUsername());
            connectionArguments.setProperty("password", original.getPassword());
            Properties historicalEffective = Driver.parseURL(original.getJdbcUrl(), connectionArguments);
            assertThat(historicalEffective).isNotNull();
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                Properties privateEffective = Driver.parseURL(url(dedicated), properties(dedicated));
                assertThat(privateEffective).isNotNull();
                for (String key : historicalEffective.stringPropertyNames()) {
                    if (!isTimeout(key)) assertThat(privateEffective.getProperty(key)).as(key).isEqualTo(historicalEffective.getProperty(key));
                }
                assertTimeouts(privateEffective);
                assertThat(privateEffective).containsEntry("socket%54imeout", "0").containsEntry("bare", "");
                assertThat(url(dedicated)).doesNotContain("?", "url-user", "secret");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:postgresql://[::1]:5432/registration_fixture",
            "jdbc:postgresql://first.example:5432,[::1]:5433/registration%20fixture",
            "jdbc:postgresql://localhost/registration%2Ffixture"})
    void preservesExplicitLocationsIncludingMultipleHostsAndIpv6(String jdbcUrl) {
        try (HikariDataSource original = source()) {
            original.setJdbcUrl(jdbcUrl + "?sslmode=require");
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                assertThat(url(dedicated)).isEqualTo(jdbcUrl);
                assertThat(properties(dedicated)).containsEntry("sslmode", "require");
                assertThat(Driver.parseURL(url(dedicated), properties(dedicated)))
                        .isEqualTo(Driver.parseURL(original.getJdbcUrl(), properties(dedicated)));
            }
        }
    }

    @Test
    void snapshotDoesNotShareMutablePropertiesCredentialsOrHooksWithItsSource() {
        try (HikariDataSource original = source()) {
            original.addDataSourceProperty("ApplicationName", "before");
            original.setThreadFactory(task -> new Thread(task, "original-factory"));
            original.setMetricsTrackerFactory(mock(MetricsTrackerFactory.class));
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                original.getDataSourceProperties().setProperty("ApplicationName", "after");
                original.setCredentials(Credentials.of("changed-user", "changed-password"));
                original.setJdbcUrl("jdbc:postgresql://different.example/changed");
                assertThat(properties(dedicated)).containsEntry("ApplicationName", "before")
                        .containsEntry("user", "application-user").containsEntry("password", "application-password");
                assertThat(url(dedicated)).isEqualTo(URL);
                assertThat(dedicated.getThreadFactory()).isNull();
                assertThat(dedicated.getMetricsTrackerFactory()).isNull();
            }
        }
    }

    @Test
    void copiedPropertiesExcludeInheritedDefaultsLikeTheHikariDriverConstructor() {
        try (HikariDataSource original = source()) {
            Properties inherited = new Properties();
            inherited.setProperty("options", "inherited-must-not-be-used");
            Properties configured = new Properties(inherited);
            configured.setProperty("ApplicationName", "explicit");
            // Hikari's setter itself copies only own entries; this additionally exercises a retained defaults object.
            ReflectionTestUtils.setField(original, "dataSourceProperties", configured);
            try (HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
                assertThat(properties(dedicated)).doesNotContainKey("options").containsEntry("ApplicationName", "explicit");
            }
        }
    }

    @Test
    void internalDriverUsesFreshPropertiesAndKeepsItsTimeoutLocalAndDiagnosticsOpaque() throws Exception {
        try (HikariDataSource original = source(); HikariDataSource dedicated = LegalRegistrationSessionPoolFactory.create(original)) {
            DataSource driver = dedicated.getDataSource();
            int globalTimeout = DriverManager.getLoginTimeout();
            driver.setLoginTimeout(1);
            assertThat(driver.getLoginTimeout()).isEqualTo(1);
            assertThat(DriverManager.getLoginTimeout()).isEqualTo(globalTimeout);
            assertThat(driver.toString()).isEqualTo("LegalRegistrationSessionDriverDataSource[redacted]");
            assertThatThrownBy(() -> driver.setLoginTimeout(2)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> driver.getConnection("replacement", "sensitive-replacement"))
                    .isExactlyInstanceOf(SQLFeatureNotSupportedException.class).hasNoCause();
            assertThatThrownBy(() -> driver.unwrap(DataSource.class)).isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
            assertThat(driver.isWrapperFor(DataSource.class)).isFalse();
            assertThat(driver.getLogWriter()).isNull();
            assertThatThrownBy(() -> driver.setLogWriter(new PrintWriter(new StringWriter())))
                    .isExactlyInstanceOf(SQLFeatureNotSupportedException.class);
            Connection physical = mock(Connection.class);
            AtomicInteger calls = new AtomicInteger();
            try (var manager = mockStatic(DriverManager.class)) {
                manager.when(() -> DriverManager.getConnection(eq(URL), any(Properties.class))).thenAnswer(call -> {
                    Properties supplied = call.getArgument(1);
                    assertThat(supplied).containsEntry("user", "application-user").containsEntry("password", "application-password");
                    supplied.setProperty("password", "mutated-by-driver");
                    calls.incrementAndGet();
                    return physical;
                });
                assertThat(driver.getConnection()).isSameAs(physical);
                assertThat(driver.getConnection()).isSameAs(physical);
                assertThat(calls).hasValue(2);
            }
            assertThat(properties(dedicated)).containsEntry("password", "application-password");
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:mysql://localhost/secret", "jdbc:postgresql:secret", "jdbc:postgresql:///secret",
            "jdbc:postgresql://localhost", "jdbc:postgresql://localhost/", "jdbc:postgresql://secret@localhost/db",
            "jdbc:postgresql://localhost:0/db", "jdbc:postgresql://localhost:65536/db",
            "jdbc:postgresql://localhost:bad/db", "jdbc:postgresql://localhost/db#secret",
            "jdbc:postgresql://localhost/extra/db", "jdbc:postgresql://localhost/bad%XX",
            "jdbc:postgresql://localhost/db?password=bad%XX", "jdbc:postgresql://localhost,,other/db"})
    void unsupportedOrMalformedLocationIsRejectedWithoutBorrowOrInputDiagnostics(String jdbcUrl) {
        try (HikariDataSource original = source()) {
            original.setJdbcUrl(jdbcUrl);
            assertInvalid(original);
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "host", "HOST", "PGHOST", "port", "dbname", "PGPORT", "PGDBNAME",
            "service", "options", "socketFactory", "socketFactoryArg", "authenticationPluginClassName",
            "sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback"})
    void queryCannotChangeNominalLocationIdentityOrTransport(String key) {
        try (HikariDataSource original = source()) {
            original.setJdbcUrl(URL + "?" + key + "=sensitive-replacement");
            assertInvalid(original);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"service", "options", "socketFactory", "authenticationPluginClassName", "sslfactory", "sslpasswordcallback"})
    void unsupportedDriverPropertiesAreRejectedAsWell(String key) {
        try (HikariDataSource original = source()) {
            original.addDataSourceProperty(key, "sensitive-replacement");
            assertInvalid(original);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"data-source", "data-source-class", "jndi", "credentials-provider", "init-sql", "isolation", "typed-property", "closed"})
    void configurationOutsideTheNominalHikariContractFailsClosed(String variant) {
        try (HikariDataSource original = source()) {
            switch (variant) {
                case "data-source" -> original.setDataSource(mock(DataSource.class));
                case "data-source-class" -> original.setDataSourceClassName("sensitive.custom.DataSource");
                case "jndi" -> original.setDataSourceJNDI("sensitive/custom-source");
                case "credentials-provider" -> original.setCredentialsProvider(mock(HikariCredentialsProvider.class));
                case "init-sql" -> original.setConnectionInitSql("SET ROLE sensitive_role");
                case "isolation" -> original.setTransactionIsolation("sensitive-invalid-isolation");
                case "typed-property" -> original.addDataSourceProperty("socketTimeout", 99);
                case "closed" -> original.close();
                default -> throw new AssertionError();
            }
            assertInvalid(original);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing-user", "blank-user", "missing-password"})
    void missingExplicitIdentityNeverFallsBackToJvmOrPgpass(String defect) {
        try (HikariDataSource original = source()) {
            original.setCredentials(Credentials.of(defect.equals("missing-user") ? null : defect.equals("blank-user") ? " " : "explicit",
                    defect.equals("missing-password") ? null : "explicit-password"));
            assertInvalid(original);
        }
    }

    @Test
    void nullOrAGetterFailureCannotRetainSensitiveDiagnosticCauses() {
        assertInvalid(null);
        HikariDataSource original = mock(HikariDataSource.class);
        when(original.isClosed()).thenThrow(new IllegalStateException("sensitive-getter-failure"));
        Throwable failure = catchThrowable(() -> LegalRegistrationSessionPoolFactory.create(original));
        assertThat(failure).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(INVALID).hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static HikariDataSource source() {
        HikariDataSource original = new HikariDataSource();
        original.setJdbcUrl(URL);
        original.setCredentials(Credentials.of("application-user", "application-password"));
        return original;
    }

    private static Properties properties(HikariDataSource dedicated) {
        return copy((Properties) ReflectionTestUtils.getField(dedicated.getDataSource(), "properties"));
    }

    private static String url(HikariDataSource dedicated) {
        return (String) ReflectionTestUtils.getField(dedicated.getDataSource(), "jdbcUrl");
    }

    private static Properties copy(Properties input) {
        Properties result = new Properties();
        result.putAll(input);
        return result;
    }

    private static boolean isTimeout(String key) {
        return switch (key) {
            case "connectTimeout", "loginTimeout", "socketTimeout", "queryTimeout", "cancelSignalTimeout" -> true;
            default -> false;
        };
    }

    private static void assertTimeouts(Properties properties) {
        assertThat(properties).containsEntry("connectTimeout", "1").containsEntry("loginTimeout", "1")
                .containsEntry("socketTimeout", "6").containsEntry("queryTimeout", "5").containsEntry("cancelSignalTimeout", "1");
    }

    private static void assertInvalid(HikariDataSource original) {
        Throwable failure = catchThrowable(() -> LegalRegistrationSessionPoolFactory.create(original));
        assertThat(failure).isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(INVALID).hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertThat(output.toString()).doesNotContain("sensitive-replacement", "application-password", "sensitive_role", "jdbc:postgresql:");
        if (original != null) assertThat(original.getHikariPoolMXBean()).isNull();
    }
}
