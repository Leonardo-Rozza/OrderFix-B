package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** Per-workshop admission held to transaction completion. Never waits behind a closure after row locks. */
@Service
public final class WorkshopClosureGate {
    public static final String LOCK_PREFIX = "ordenfix:closure:";
    private final JdbcTemplate jdbc;
    private volatile String databaseProduct;

    public WorkshopClosureGate(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);

    }

    public void requireOperational(long tallerId) { require(tallerId,false); }

    /** The authenticated account reader has already checked ADMIN, verification and current epoch. */
    public void requireAccountAccess(long tallerId) { require(tallerId,true); }

    private void require(long tallerId, boolean accountAccess) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) check(tallerId,accountAccess);
            else {
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setTimeout(5);
                transaction.executeWithoutResult(status -> check(tallerId,accountAccess));
            }
        } catch (WorkshopClosureBlockedException | WorkshopClosureBusyException failure) { throw failure; }
        catch (RuntimeException failure) { throw new WorkshopClosureBusyException(); }
    }

    private void check(long tallerId, boolean accountAccess) {
        if (tallerId <= 0) throw new WorkshopClosureBlockedException();
        String permitted="activo AND (cierre_estado='ABIERTO'"+(accountAccess
                ? " OR (cierre_estado='RESTRINGIDO' AND cierre_confirmado_en<=CURRENT_TIMESTAMP AND cierre_reversible_hasta>CURRENT_TIMESTAMP)" : "")+")";
        if (isH2()) {
            // H2 is a test dependency only; serialize on the anchor instead of emulating PostgreSQL advisory locks.
            var states = jdbc.queryForList("SELECT "+permitted+" FROM talleres WHERE id=? FOR UPDATE", Boolean.class, tallerId);
            if (states.size()!=1 || !Boolean.TRUE.equals(states.getFirst())) throw new WorkshopClosureBlockedException();
            return;
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                Boolean.class, LOCK_PREFIX + tallerId))) throw new WorkshopClosureBusyException();
        permitted=permitted.replace("CURRENT_TIMESTAMP","clock_timestamp()");
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        boolean readCommitted="read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class));
        if (readOnly && !readCommitted) throw new WorkshopClosureBusyException();
        // RC takes a fresh snapshot after the advisory admission; no row lock that CRUD must later upgrade.
        // Existing writable RR snapshots lock the permanent anchor to reject intervening transitions.
        var states = jdbc.queryForList("SELECT "+permitted+" FROM public.talleres WHERE id=?"
                + (readCommitted ? "" : " FOR SHARE"), Boolean.class, tallerId);
        if (states.size() != 1 || !Boolean.TRUE.equals(states.getFirst())) throw new WorkshopClosureBlockedException();
    }

    private boolean isH2() {
        String product=databaseProduct;
        if (product==null) databaseProduct=product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        if (!"H2".equals(product) && !"PostgreSQL".equals(product)) throw new WorkshopClosureBusyException();
        return "H2".equals(product);
    }

    /** Must be the first lock in the closure transaction, before authorization/user/job locks. */
    public void lockExclusive(long tallerId) {
        if (tallerId <= 0 || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new WorkshopClosureBusyException();
        jdbc.queryForObject("SELECT pg_catalog.set_config('lock_timeout','2s',true)",String.class);
        jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?,0))", LOCK_PREFIX + tallerId);
    }
}
