package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.UtilityElf;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/** A composition-time snapshot; subsequent JMX/credential changes require recomposition. */
final class LegalRegistrationSessionPoolFactory {
    private static final String INVALID = "La configuración de sesión de registro no es compatible";
    private static final String POSTGRES_PREFIX = "jdbc:postgresql://";
    private static final Set<String> LOCATION_KEYS = Set.of("host", "port", "dbname", "pghost", "pgport", "pgdbname");
    private static final Set<String> UNSUPPORTED_PROPERTIES = Set.of(
            "service", "options", "socketFactory", "socketFactoryArg", "authenticationPluginClassName",
            "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback");
    private static final Map<String, String> TIMEOUTS = Map.of(
            "connectTimeout", "1", "loginTimeout", "1", "socketTimeout", "6",
            "queryTimeout", "5", "cancelSignalTimeout", "1");

    private LegalRegistrationSessionPoolFactory() { }

    static HikariDataSource create(HikariDataSource original) {
        try {
            if (original == null || original.isClosed() || original.getDataSource() != null
                    || original.getDataSourceClassName() != null || original.getDataSourceJNDI() != null
                    || original.getCredentialsProvider() != null || original.getCredentialsProviderClassName() != null
                    || original.getDriverClassName() != null && !original.getDriverClassName().equals("org.postgresql.Driver")
                    || original.getConnectionInitSql() != null && !original.getConnectionInitSql().isBlank()) {
                throw invalid();
            }
            Properties properties = copyProperties(original.getDataSourceProperties());
            var credentials = original.getCredentials();
            if (credentials == null) throw invalid();
            String username = credentials.getUsername();
            String password = credentials.getPassword();
            // PoolBase calls the two-argument DriverDataSource overload only for non-null username.
            // That overload replaces non-null credentials; its no-arg path retains explicit props.
            if (username != null) {
                properties.setProperty("user", username);
                if (properties.containsKey("username")) properties.setProperty("username", username);
                if (password != null) properties.setProperty("password", password);
            } else if (password != null && !properties.containsKey("password")) {
                properties.setProperty("password", password);
            }
            String jdbcUrl = splitUrl(original.getJdbcUrl(), properties);
            validateProperties(properties);
            if (properties.getProperty("user") == null || properties.getProperty("user").isBlank()
                    || properties.getProperty("password") == null) throw invalid();
            // No global DriverManager timeout: PoolBase sees our local DataSource implementation.
            TIMEOUTS.forEach(properties::setProperty);
            DataSource driver = new RegistrationDriverDataSource(jdbcUrl, properties);
            HikariConfig config = new HikariConfig();
            config.setPoolName("legal-registration-session");
            config.setDataSource(driver);
            config.setMinimumIdle(0);
            config.setMaximumPoolSize(2);
            config.setConnectionTimeout(1_000);
            config.setValidationTimeout(1_000);
            config.setInitializationFailTimeout(-1);
            boolean autoCommit = original.isAutoCommit();
            config.setAutoCommit(autoCommit);
            if (!autoCommit) {
                // pgjdbc setSchema can begin a transaction. Hikari executes this fixed internal
                // query after applying base state and commits initialization before lending it.
                // No SQL supplied by the historical pool is copied or executed here.
                config.setIsolateInternalQueries(true);
                config.setConnectionInitSql("SELECT 1");
            }
            config.setReadOnly(original.isReadOnly());
            String isolation = original.getTransactionIsolation();
            if (isolation != null && !isolation.isEmpty()) UtilityElf.getTransactionIsolation(isolation);
            config.setTransactionIsolation(isolation);
            config.setCatalog(original.getCatalog());
            config.setSchema(original.getSchema());
            return new HikariDataSource(config);
        } catch (RuntimeException failure) {
            // Configuration inputs and driver errors may contain URL/password data. Do not attach them.
            throw invalid();
        }
    }

    private static Properties copyProperties(Properties source) {
        if (source == null) throw invalid();
        Properties result = new Properties();
        // Hikari DriverDataSource uses putAll, excluding inherited Properties defaults.
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) throw invalid();
            result.setProperty(key, value);
        }
        return result;
    }

    private static String splitUrl(String original, Properties properties) {
        if (original == null || !original.startsWith(POSTGRES_PREFIX)) throw invalid();
        int queryIndex = original.indexOf('?');
        String base = queryIndex < 0 ? original : original.substring(0, queryIndex);
        validateBaseUrl(base);
        if (queryIndex >= 0) {
            for (String token : original.substring(queryIndex + 1).split("&")) {
                if (token.isEmpty()) continue;
                int equals = token.indexOf('=');
                String key = equals < 0 ? token : token.substring(0, equals);
                // pgjdbc decodes values, not keys. In particular socket%54imeout is not socketTimeout.
                if (key.isEmpty() || LOCATION_KEYS.contains(key.toLowerCase(Locale.ROOT))) throw invalid();
                String value = equals < 0 ? "" : URLDecoder.decode(token.substring(equals + 1), StandardCharsets.UTF_8);
                properties.setProperty(key, value); // pgjdbc retains the last duplicate query key.
            }
        }
        return base;
    }

    private static void validateBaseUrl(String base) {
        String serverAndDatabase = base.substring(POSTGRES_PREFIX.length());
        int slash = serverAndDatabase.indexOf('/');
        if (slash < 1 || slash == serverAndDatabase.length() - 1
                || serverAndDatabase.indexOf('/', slash + 1) >= 0) throw invalid();
        String database = serverAndDatabase.substring(slash + 1);
        // Explicit hosts/database avoid identity or target fallback from driver defaults/service files.
        // Validate each host separately so pgjdbc's comma-separated hosts and bracketed IPv6 survive.
        for (String host : serverAndDatabase.substring(0, slash).split(",", -1)) {
            URI uri = URI.create("postgresql://" + host + "/" + database);
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65_535
                    || !uri.getRawPath().equals("/" + database)) throw invalid();
        }
        // Match the driver's database URL decoding, including rejection of malformed percent escapes.
        if (URLDecoder.decode(database, StandardCharsets.UTF_8).isEmpty()) throw invalid();
    }

    private static void validateProperties(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (UNSUPPORTED_PROPERTIES.contains(key)
                    || key.equals("sslfactory") && !properties.getProperty(key).equals("org.postgresql.ssl.LibPQFactory")) {
                throw invalid();
            }
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }

    /** Opaque to Hikari diagnostics; no query or credentials are embedded in the JDBC URL. */
    private static final class RegistrationDriverDataSource implements DataSource {
        private final String jdbcUrl;
        private final Properties properties;

        private RegistrationDriverDataSource(String jdbcUrl, Properties properties) {
            this.jdbcUrl = jdbcUrl;
            this.properties = copyProperties(properties);
        }

        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, copyProperties(properties));
        }

        @Override public Connection getConnection(String username, String password) throws SQLException { throw unsupported(); }
        @Override public int getLoginTimeout() { return 1; }
        @Override public void setLoginTimeout(int seconds) throws SQLException { if (seconds != 1) throw unsupported(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw unsupported(); }
        @Override public Logger getParentLogger() { return Logger.getLogger("ordenfix.legal.registration.session"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { throw unsupported(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public String toString() { return "LegalRegistrationSessionDriverDataSource[redacted]"; }
        private static SQLFeatureNotSupportedException unsupported() {
            return new SQLFeatureNotSupportedException("Operación de configuración de sesión no admitida");
        }
    }
}
