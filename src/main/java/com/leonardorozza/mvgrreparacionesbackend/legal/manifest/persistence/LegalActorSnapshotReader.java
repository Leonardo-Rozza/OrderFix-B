package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Stabilizes the authenticated actor after the caller's accredited shared editorial gate. */
final class LegalActorSnapshotReader {

    static final String ACTOR_LOCK_NAMESPACE = "ordenfix:legal-actor:v1";
    private static final String LOCK_SQL = """
            SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(
                pg_catalog.jsonb_build_array('ordenfix:legal-actor:v1', ?::bigint, ?::bigint)::text, 0))
            """;
    private static final String WORKSHOP_SQL = """
            SELECT id, activo FROM public.talleres WHERE id = ? LIMIT 2 FOR SHARE
            """;
    private static final String USER_SQL = """
            SELECT id, taller_id, role, active, token_version
              FROM public.users WHERE id = ? AND taller_id = ? LIMIT 2 FOR SHARE
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    LegalActorSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
    }

    boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    LegalActorSnapshot read(AuthenticatedUserPrincipal principal, LegalEditorialTimeBoundary boundary,
                           LegalPrivateRequirementsDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline").check();
        Objects.requireNonNull(boundary, "boundary");
        PrincipalIdentity identity = identify(principal);
        requireBoundary(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED)
                && TransactionSynchronizationManager.hasResource(dataSource));
        try {
            return Objects.requireNonNull(jdbc.execute((ConnectionCallback<LegalActorSnapshot>) connection -> {
                requireBoundary(!connection.getAutoCommit() && !connection.isReadOnly()
                        && connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED
                        && DataSourceUtils.isConnectionTransactional(
                                DataSourceUtils.getTargetConnection(connection), dataSource));
                acquireActorLock(connection, identity, deadline);
                lockWorkshop(connection, identity, deadline);
                return lockUser(connection, identity, deadline);
            }));
        } catch (LegalActorSnapshotException | LegalPrivateRequirementsReadException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalPrivateRequirementsReadException(failure);
        }
    }

    private static PrincipalIdentity identify(AuthenticatedUserPrincipal principal) {
        requireActor(principal != null && principal.getUserId() != null && principal.getUserId() > 0
                && principal.getTallerId() != null && principal.getTallerId() > 0
                && principal.getTokenVersion() >= 0 && principal.isEnabled()
                && principal.isAccountNonExpired() && principal.isAccountNonLocked()
                && principal.isCredentialsNonExpired() && principal.getAuthorities().size() == 1);
        String authority = principal.getAuthorities().iterator().next().getAuthority();
        UserRole role = switch (Objects.toString(authority, "")) {
            case "ROLE_ADMIN" -> UserRole.ADMIN;
            case "ROLE_USER" -> UserRole.USER;
            default -> throw new LegalActorSnapshotException();
        };
        return new PrincipalIdentity(principal.getUserId(), principal.getTallerId(), role,
                principal.getTokenVersion());
    }

    private static void acquireActorLock(Connection connection, PrincipalIdentity identity,
                                         LegalPrivateRequirementsDeadline deadline) throws SQLException {
        queryOne(connection, LOCK_SQL, new long[]{identity.tallerId(), identity.userId()}, false, rows -> null,
                deadline);
    }

    private static void lockWorkshop(Connection connection, PrincipalIdentity identity,
                                     LegalPrivateRequirementsDeadline deadline) throws SQLException {
        queryOne(connection, WORKSHOP_SQL, new long[]{identity.tallerId()}, true, rows -> {
            requireActor(Objects.equals(rows.getObject("id", Long.class), identity.tallerId())
                    && Boolean.TRUE.equals(rows.getObject("activo", Boolean.class)));
            return null;
        }, deadline);
    }

    private static LegalActorSnapshot lockUser(Connection connection, PrincipalIdentity identity,
                                               LegalPrivateRequirementsDeadline deadline) throws SQLException {
        return queryOne(connection, USER_SQL, new long[]{identity.userId(), identity.tallerId()}, true, rows -> {
            requireActor(Objects.equals(rows.getObject("id", Long.class), identity.userId())
                    && Objects.equals(rows.getObject("taller_id", Long.class), identity.tallerId())
                    && identity.role().name().equals(rows.getString("role"))
                    && Boolean.TRUE.equals(rows.getObject("active", Boolean.class))
                    && Objects.equals(rows.getObject("token_version", Long.class), identity.tokenVersion()));
            return new LegalActorSnapshot(identity.userId(), identity.tallerId(), identity.role(),
                    identity.tokenVersion(), true, true);
        }, deadline);
    }

    /** One expected row, an explicit second-row sentinel and lexical resource ownership. */
    private static <T> T queryOne(Connection connection, String sql, long[] arguments, boolean actorRow,
                                   RowReader<T> reader, LegalPrivateRequirementsDeadline deadline)
            throws SQLException {
        deadline.check();
        try (PreparedStatement statement = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            try {
                statement.setFetchSize(32);
                statement.setMaxRows(2);
                for (int index = 0; index < arguments.length; index++) {
                    statement.setLong(index + 1, arguments[index]);
                }
                deadline.check();
                try (ResultSet rows = statement.executeQuery()) {
                    deadline.check();
                    boolean present = rows.next();
                    deadline.check();
                    if (actorRow) {
                        requireActor(present);
                    } else {
                        requireBoundary(present);
                    }
                    T result = reader.read(rows);
                    deadline.check();
                    requireBoundary(!rows.next());
                    deadline.check();
                    return result;
                }
            } catch (SQLException | RuntimeException failure) {
                deadline.cancel(statement);
                throw failure;
            }
        }
    }

    private static void requireActor(boolean condition) {
        if (!condition) {
            throw new LegalActorSnapshotException();
        }
    }

    private static void requireBoundary(boolean condition) {
        if (!condition) {
            throw new LegalPrivateRequirementsReadException();
        }
    }

    private record PrincipalIdentity(long userId, long tallerId, UserRole role, long tokenVersion) { }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rows) throws SQLException;
    }
}
