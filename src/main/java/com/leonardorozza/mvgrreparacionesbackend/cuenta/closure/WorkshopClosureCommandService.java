package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Internal command. HTTP exposure and real provider adapters belong to later cuts. */
@Service
public final class WorkshopClosureCommandService {
    private final JdbcTemplate jdbc;
    private final WorkshopClosureGate gate;
    private final WorkshopClosureReauthenticationService authorization;
    private final WorkshopClosureStore store;
    private final WorkshopClosureEffects effects;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WorkshopClosureCommandService(JdbcTemplate jdbc, WorkshopClosureGate gate,
            WorkshopClosureReauthenticationService authorization, WorkshopClosureStore store,
            WorkshopClosureEffects effects, PlatformTransactionManager manager, Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.gate=Objects.requireNonNull(gate);
        this.authorization=Objects.requireNonNull(authorization);
        this.store=Objects.requireNonNull(store);
        this.effects=Objects.requireNonNull(effects);
        this.clock=Objects.requireNonNull(clock);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /** Replay needs a current session, but never consumes another password proof or changes the receipt. */
    public Receipt execute(String accessToken, String proof, WorkshopClosurePurpose purpose,
            UUID operationId, UUID closureReference, String confirmation) {
        validate(purpose,operationId,closureReference,confirmation);
        String digest=hash("ordenfix-cierre-command/1\n"+purpose+"\n"+closureReference+"\n"+confirmation+"\n"+WorkshopClosurePolicy.POLICY_VERSION);
        try {
            long tallerId=authorization.routeWorkshop(accessToken);
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL statement_timeout='5s'");
                // Never acquire a user, workshop or outbox row lock before this gate.
                gate.lockExclusive(tallerId);
                var actor=authorization.authorize(accessToken);
                if(actor.tallerId()!=tallerId) throw rejected(Rejected.Code.SESSION_INVALID);
                var previous=read(operationId);
                if(previous!=null) {
                    if(previous.tallerId()!=tallerId || previous.userId()!=actor.userId()
                            || previous.receipt().purpose()!=purpose || !previous.receipt().reference().equals(closureReference)
                            || !previous.digest().equals(digest)) throw rejected(Rejected.Code.CONFLICT);
                    authorization.requireLive(actor);
                    return previous.receipt().asReused();
                }
                var confirmed=authorization.consume(accessToken,proof,purpose,operationId,closureReference);
                if(!sameIdentity(actor,confirmed)) throw rejected(Rejected.Code.SESSION_INVALID);
                var transition=purpose==WorkshopClosurePurpose.CERRAR
                        ? store.restrict(tallerId,actor.userId(),closureReference)
                        : store.restore(tallerId,actor.userId(),closureReference);
                if(transition.reused()) throw rejected(Rejected.Code.CONFLICT);
                long generation=Math.addExact(actor.closureVersion(),1L);
                String state=purpose==WorkshopClosurePurpose.CERRAR ? "RESTRINGIDO" : "ABIERTO";
                Instant completedAt=clock.instant().truncatedTo(ChronoUnit.MICROS);
                jdbc.update("""
                        INSERT INTO public.cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,
                          cierre_referencia,cierre_version,request_digest,proof_hash,estado_resultante,politica,
                          confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,operationId,tallerId,actor.userId(),purpose.name(),closureReference,generation,digest,hash(proof),
                        state,WorkshopClosurePolicy.POLICY_VERSION,utc(transition.confirmedAt()),utc(transition.reversibleUntil()),
                        utc(transition.deletionExpectedBy()),utc(completedAt));
                if(purpose==WorkshopClosurePurpose.CERRAR) {
                    effects.enqueueClose(operationId,closureReference,tallerId,actor.userId(),completedAt);
                } else {
                    effects.enqueueRestore(operationId,closureReference,tallerId,actor.userId(),completedAt);
                }
                // Our own transition revoked the epoch. Check the original deadlines, not that old epoch.
                authorization.requireLive(confirmed);
                return new Receipt(operationId,closureReference,purpose,generation,state,transition.confirmedAt(),
                        transition.reversibleUntil(),transition.deletionExpectedBy(),completedAt,false);
            }));
        } catch(Rejected rejected) { throw rejected; }
        catch(UnauthorizedException rejected) { throw rejected(Rejected.Code.SESSION_INVALID); }
        catch(AccessDeniedException rejected) { throw rejected(Rejected.Code.FORBIDDEN); }
        catch(BadRequestException rejected) { throw rejected(Rejected.Code.CONFIRMATION_INVALID); }
        catch(WorkshopClosureBlockedException rejected) { throw rejected(Rejected.Code.CONFLICT); }
        catch(WorkshopClosureBusyException rejected) { throw rejected(Rejected.Code.UNAVAILABLE); }
        catch(WorkshopClosureStore.Rejected rejected) {
            throw rejected(rejected.code()==WorkshopClosureStore.Rejected.Code.CAPACITY
                    ? Rejected.Code.CAPACITY : rejected.code()==WorkshopClosureStore.Rejected.Code.CONFLICT
                    ? Rejected.Code.CONFLICT : Rejected.Code.UNAVAILABLE);
        }
        // Different workshop gates can race for the same globally unique operation identifier.
        catch(DuplicateKeyException collision) { throw rejected(Rejected.Code.CONFLICT); }
        catch(ClosurePreparationException failure) {
            throw rejected(failure.code()==ClosurePreparationException.Code.CAPACITY_EXCEEDED
                    ? Rejected.Code.CAPACITY : Rejected.Code.UNAVAILABLE);
        }
        catch(RuntimeException unavailable) { throw rejected(Rejected.Code.UNAVAILABLE); }
    }

    public record Receipt(UUID operationId, UUID reference, WorkshopClosurePurpose purpose, long generation,
            String stateAtCommit, Instant confirmedAt, Instant reversibleUntil, Instant deletionExpectedBy,
            Instant completedAt, boolean reused) {
        private Receipt asReused() { return new Receipt(operationId,reference,purpose,generation,stateAtCommit,
                confirmedAt,reversibleUntil,deletionExpectedBy,completedAt,true); }
    }
    public static final class Rejected extends RuntimeException {
        public enum Code { SESSION_INVALID, FORBIDDEN, CONFIRMATION_INVALID, CONFLICT, CAPACITY, UNAVAILABLE }
        private final Code code;
        private Rejected(Code code) { super("No se pudo confirmar la operación del taller."); this.code=code; }
        public Code code() { return code; }
    }
    private Stored read(UUID operationId) {
        var rows=jdbc.query("""
                SELECT operacion_id,cierre_referencia,proposito,cierre_version,estado_resultante,
                       confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en,
                       taller_id,user_id,request_digest
                  FROM public.cuenta_cierre_operaciones WHERE operacion_id=?
                """,(rs,n)->new Stored(new Receipt(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    WorkshopClosurePurpose.valueOf(rs.getString(3)),rs.getLong(4),rs.getString(5),
                    rs.getObject(6,OffsetDateTime.class).toInstant(),rs.getObject(7,OffsetDateTime.class).toInstant(),
                    rs.getObject(8,OffsetDateTime.class).toInstant(),rs.getObject(9,OffsetDateTime.class).toInstant(),false),
                    rs.getLong(10),rs.getLong(11),rs.getString(12)),operationId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private record Stored(Receipt receipt,long tallerId,long userId,String digest) { }
    private static boolean sameIdentity(WorkshopClosureReauthenticationService.Authority first,
            WorkshopClosureReauthenticationService.Authority second) {
        return first.userId()==second.userId() && first.tallerId()==second.tallerId()
                && first.tokenVersion()==second.tokenVersion() && first.closureVersion()==second.closureVersion()
                && first.sessionHash().equals(second.sessionHash()) && first.state().equals(second.state())
                && Objects.equals(first.closureReference(),second.closureReference());
    }
    private static void validate(WorkshopClosurePurpose purpose,UUID operationId,UUID reference,String confirmation) {
        if(purpose==null || operationId==null || reference==null
                || (purpose==WorkshopClosurePurpose.CERRAR && !operationId.equals(reference))
                || !(purpose==WorkshopClosurePurpose.CERRAR ? "CERRAR MI TALLER" : "RESTAURAR MI TALLER").equals(confirmation))
            throw rejected(Rejected.Code.CONFIRMATION_INVALID);
    }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException unavailable) { throw rejected(Rejected.Code.UNAVAILABLE); }
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static Rejected rejected(Rejected.Code code) { return new Rejected(code); }
}
