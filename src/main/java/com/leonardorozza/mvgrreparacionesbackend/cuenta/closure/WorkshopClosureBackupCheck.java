package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Partial, read-only comparison. Supplied evidence is not authenticated and never authorizes reopening. */
@Service
public final class WorkshopClosureBackupCheck {
    public enum State { RESTRICTED, RESTORED, DELETED }
    public enum Status { COMPARACION_COMPATIBLE, DIVERGENCIAS }
    public enum Notice { NO_AUTORIZA_REAPERTURA }
    public enum Issue {
        DATABASE_MISSING, DATABASE_BEHIND, DATABASE_AHEAD, LOCAL_INCONSISTENCY,
        REFERENCE_OR_STATE_DIVERGENT, OWNER_DIVERGENT, OWNER_EPOCH_BEHIND, OWNER_EPOCH_AHEAD,
        DELETION_NOT_IMPLEMENTED
    }
    public record Evidence(long tallerId,long titularId,UUID reference,long generation,State state,long ownerEpoch) {
        @Override public String toString(){return "ClosureBackupEvidence[redacted]";}
    }
    public record Finding(long tallerId,Issue issue) {
        @Override public String toString(){return "ClosureBackupFinding["+issue+"]";}
    }
    public record Report(Status status,Instant observedAt,int checked,List<Finding> findings,Notice notice) {
        public Report {findings=List.copyOf(findings);}
        @Override public String toString(){return "ClosureBackupReport["+status+", checked="+checked+", "+notice+"]";}
    }
    public static final class Rejected extends RuntimeException {
        public enum Code { INVALID_INPUT, CAPACITY_EXCEEDED, UNAVAILABLE }
        private final Code code;
        public Rejected(Code code){super("No se pudo comparar la evidencia de recuperación.");this.code=Objects.requireNonNull(code);}
        public Code code(){return code;}
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public WorkshopClosureBackupCheck(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=Objects.requireNonNull(jdbc);
        transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);transaction.setTimeout(10);
    }
    public Report compare(List<Evidence> supplied) {
        List<Evidence> evidence=validated(supplied);
        try {
            return Objects.requireNonNull(transaction.execute(status->{
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                Long[] ids=evidence.stream().map(Evidence::tallerId).toArray(Long[]::new);
                var snapshots=jdbc.query(QUERY,statement->statement.setArray(1,
                        statement.getConnection().createArrayOf("bigint",ids)),WorkshopClosureBackupCheck::snapshot);
                if(snapshots.size()!=evidence.size())throw new Rejected(Rejected.Code.UNAVAILABLE);
                Map<Long,Snapshot> byWorkshop=new HashMap<>();
                Instant observed=snapshots.getFirst().observedAt();
                for(var row:snapshots) {
                    if(byWorkshop.put(row.id(),row)!=null || !observed.equals(row.observedAt()))
                        throw new Rejected(Rejected.Code.UNAVAILABLE);
                }
                List<Finding> findings=new ArrayList<>();
                for(Evidence expected:evidence) {
                    Snapshot actual=byWorkshop.get(expected.tallerId());
                    if(actual==null)throw new Rejected(Rejected.Code.UNAVAILABLE);
                    for(Issue issue:compare(expected,actual))findings.add(new Finding(expected.tallerId(),issue));
                }
                return new Report(findings.isEmpty()?Status.COMPARACION_COMPATIBLE:Status.DIVERGENCIAS,
                        observed,evidence.size(),findings,Notice.NO_AUTORIZA_REAPERTURA);
            }));
        } catch(Rejected rejected){throw rejected;}
        catch(RuntimeException failure){throw new Rejected(Rejected.Code.UNAVAILABLE);}
    }
    private static List<Evidence> validated(List<Evidence> supplied) {
        if(supplied==null || supplied.isEmpty())throw new Rejected(Rejected.Code.INVALID_INPUT);
        if(supplied.size()>1000)throw new Rejected(Rejected.Code.CAPACITY_EXCEEDED);
        List<Evidence> copy;
        try {copy=List.copyOf(supplied);}catch(RuntimeException invalid){throw new Rejected(Rejected.Code.INVALID_INPUT);}
        if(copy.size()>1000)throw new Rejected(Rejected.Code.CAPACITY_EXCEEDED);
        Set<Long> workshops=new HashSet<>();Set<UUID> references=new HashSet<>();
        for(Evidence item:copy) {
            if(item.tallerId()<=0 || item.titularId()<=0 || item.reference()==null || item.generation()<=0
                    || item.state()==null || item.ownerEpoch()<0 || !workshops.add(item.tallerId()) || !references.add(item.reference()))
                throw new Rejected(Rejected.Code.INVALID_INPUT);
        }
        return copy;
    }
    static List<Issue> compare(Evidence expected,Snapshot actual) {
        List<Issue> issues=new ArrayList<>();
        // V34 has no supported terminal deletion transition. Equal metadata could not accredit deletion.
        if(expected.state()==State.DELETED)issues.add(Issue.DELETION_NOT_IMPLEMENTED);
        if(!actual.exists()){issues.add(Issue.DATABASE_MISSING);return List.copyOf(issues);}
        if(!actual.coherent())issues.add(Issue.LOCAL_INCONSISTENCY);
        if(actual.generation()<expected.generation())issues.add(Issue.DATABASE_BEHIND);
        else if(actual.generation()>expected.generation())issues.add(Issue.DATABASE_AHEAD);
        else {
            String state=expected.state()==State.RESTRICTED?"RESTRINGIDO":expected.state()==State.RESTORED?"ABIERTO":"ELIMINADO";
            if(!state.equals(actual.state()) || !expected.reference().equals(actual.reference()))issues.add(Issue.REFERENCE_OR_STATE_DIVERGENT);
        }
        // Before the first local closure there is no history-bound owner to compare.
        if(actual.generation()>0) {
            if(actual.ownerId()==null || actual.ownerWorkshop()==null || actual.ownerId()!=expected.titularId()
                    || actual.ownerWorkshop()!=expected.tallerId() || !"ADMIN".equals(actual.ownerRole()) || actual.ownerEpoch()==null)
                issues.add(Issue.OWNER_DIVERGENT);
            else if(actual.ownerEpoch()<expected.ownerEpoch())issues.add(Issue.OWNER_EPOCH_BEHIND);
            else if(actual.ownerEpoch()>expected.ownerEpoch())issues.add(Issue.OWNER_EPOCH_AHEAD);
        }
        return List.copyOf(issues);
    }
    record Snapshot(long id,Instant observedAt,boolean exists,long generation,String state,UUID reference,
                    Long ownerId,Long ownerWorkshop,String ownerRole,Long ownerEpoch,boolean coherent) {
        @Override public String toString(){return "ClosureBackupSnapshot[redacted]";}
    }
    private static Snapshot snapshot(ResultSet rs,int row)throws SQLException {
        return new Snapshot(rs.getLong("requested_id"),rs.getObject("observed_at",OffsetDateTime.class).toInstant(),
                rs.getObject("actual_id")!=null,rs.getLong("cierre_version"),rs.getString("cierre_estado"),
                rs.getObject("history_reference",UUID.class),rs.getObject("owner_id",Long.class),rs.getObject("owner_workshop",Long.class),
                rs.getString("owner_role"),rs.getObject("owner_epoch",Long.class),rs.getBoolean("coherent"));
    }
    private static final String QUERY="""
            WITH requested AS (SELECT unnest(?::bigint[]) AS requested_id)
            SELECT q.requested_id,statement_timestamp() AS observed_at,t.id AS actual_id,t.cierre_version,t.cierre_estado,
                   h.referencia AS history_reference,u.id AS owner_id,u.taller_id AS owner_workshop,u.role AS owner_role,u.token_version AS owner_epoch,
                   CASE
                    WHEN t.cierre_estado='ABIERTO' AND t.cierre_version=0 THEN h.referencia IS NULL
                      AND t.cierre_referencia IS NULL AND t.cierre_confirmado_en IS NULL AND t.cierre_reversible_hasta IS NULL AND t.cierre_eliminacion_prevista_en IS NULL
                    WHEN t.cierre_estado='ABIERTO' AND t.cierre_version>0 THEN h.estado='RESTAURADO' AND h.restaurado_en IS NOT NULL
                      AND t.cierre_referencia IS NULL AND t.cierre_confirmado_en IS NULL AND t.cierre_reversible_hasta IS NULL AND t.cierre_eliminacion_prevista_en IS NULL
                    WHEN t.cierre_estado='RESTRINGIDO' THEN h.estado='RESTRINGIDO' AND h.restaurado_en IS NULL
                      AND h.referencia=t.cierre_referencia AND h.confirmado_en=t.cierre_confirmado_en
                      AND h.reversible_hasta=t.cierre_reversible_hasta AND h.eliminacion_prevista_en=t.cierre_eliminacion_prevista_en
                    ELSE false END AS coherent
              FROM requested q LEFT JOIN public.talleres t ON t.id=q.requested_id
              LEFT JOIN public.cuenta_cierres h ON h.taller_id=t.id
                AND h.generacion=CASE WHEN t.cierre_estado='ABIERTO' THEN t.cierre_version-1 ELSE t.cierre_version END
              LEFT JOIN public.users u ON u.id=h.titular_id ORDER BY q.requested_id
            """;
}
