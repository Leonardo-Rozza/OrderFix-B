package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Internal authenticated acceptance: one bounded transaction, no HTTP, JPA, retry or external effects. */
public final class LegalAcceptanceService {
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceTransactionBoundary boundary;
    private final LegalApplicableScopeResolver resolver;
    private final LegalRequiredSetAggregateStore aggregates;
    private final LegalPrivateRequirementsReader requirements;
    private final LegalAcceptanceEvidenceReader evidence;
    private final LegalIdempotencyCoordinator coordinator;
    private final LegalAcceptanceWriter writer;
    private final LegalAcceptanceSelection selection = new LegalAcceptanceSelection();

    LegalAcceptanceService(JdbcTemplate jdbc, LegalAcceptanceTransactionBoundary boundary,
                           LegalApplicableScopeResolver resolver, LegalRequiredSetAggregateStore aggregates,
                           LegalPrivateRequirementsReader requirements, LegalAcceptanceEvidenceReader evidence,
                           LegalV29AcceptanceSchemaVerifier schema, LegalAcceptanceKeyConfiguration keys) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(keys, "keys");
        boundary.requireExactBoundary();
        if (jdbc.getDataSource() == null || !boundary.usesJdbc(jdbc) || !aggregates.usesJdbc(jdbc)
                || !requirements.usesJdbc(jdbc) || !evidence.usesJdbc(jdbc) || !schema.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("La aceptación requiere colaboradores en una única frontera JDBC");
        }
        var results = new LegalIdempotencyResultStore(jdbc);
        coordinator = new LegalIdempotencyCoordinator(jdbc, schema, keys.keyring(), results);
        writer = new LegalAcceptanceWriter(jdbc, keys.codec(), keys.retentionPolicy(), results);
    }

    /** Principal and metadata must come from the server bridge; browser inputs cannot choose authority. */
    public LegalAcceptanceReceipt accept(AuthenticatedUserPrincipal principal, String idempotencyKey,
                                          String requiredSetRevision, List<Acceptance> acceptances,
                                          LegalRequestMetadata metadata) {
        var completion = new LegalTransactionCompletionState<LegalAcceptanceReceipt>();
        try {
            LegalActorSnapshot identity = identify(principal);
            if (metadata == null) throw new IllegalStateException("La captura legal requerida no está disponible");
            return boundary.execute(completion, (status, deadline) -> {
                observeActor(identity);
                final com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand command;
                try {
                    LegalAcceptanceCommandValidator.requireIdempotencyKey(idempotencyKey);
                    command = LegalAcceptanceCommandValidator.authenticated(identity, requiredSetRevision, acceptances);
                } catch (IllegalArgumentException invalidShape) {
                    throw new LegalAcceptanceFailure(LegalAcceptanceFailure.Reason.INVALID_PAYLOAD,
                            completion.snapshot(), invalidShape);
                }
                var reservation = coordinator.reserve(command, idempotencyKey, deadline::remainingMillis);
                var replay = reservation.replay();
                if (replay.isPresent()) {
                    var result = replay.get();
                    return new LegalAcceptanceReceipt(LegalAcceptanceReceipt.Kind.valueOf(result.result()), true,
                            result.lotId(), result.acceptanceIds());
                }
                var editorial = boundary.enterEditorialShared(reservation, deadline);
                reservation.readBudget();
                jdbc.queryForList("""
                        SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
                          pg_catalog.jsonb_build_array('ordenfix:legal-actor:v1',?::bigint,?::bigint)::text,0))
                        """, identity.tallerId(), identity.userId());
                reservation.requireWriteActor(identity);
                reservation.readBudget();
                OffsetDateTime observedAt = Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT statement_timestamp()", OffsetDateTime.class));
                var observation = new LegalEditorialTimeBoundary(editorial.transactionAt(), observedAt.toInstant());
                var scopes = resolver.resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                        LocaleLegal.ES_AR, identity.audience());
                deadline.check();
                var aggregate = aggregates.materialize(scopes, observation);
                var current = requirements.read(identity, scopes, aggregate, observation, deadline);
                var existing = evidence.read(reservation, identity);
                deadline.check();
                var chosen = selection.select(command, current, existing);
                var result = writer.write(reservation, identity, aggregate, chosen, metadata);
                reservation.readBudget();
                // Accredit deferred graph constraints while rollback remains an observable outcome.
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                deadline.check();
                return result;
            });
        } catch (RuntimeException failure) {
            LegalAcceptanceFailure.Reason reason = classify(failure);
            RuntimeException cause = failure instanceof LegalAcceptanceFailure typed
                    && typed.getCause() instanceof RuntimeException inner ? inner : failure;
            // A late cleanup/deadline failure retains COMMITTED; an invoked but failed COMMIT stays UNKNOWN.
            throw new LegalAcceptanceFailure(reason, completion.snapshot(), cause);
        }
    }

    private void observeActor(LegalActorSnapshot actor) {
        var rows = jdbc.query("""
                SELECT u.taller_id,u.role,u.active,u.token_version,t.activo
                  FROM public.users u JOIN public.talleres t ON t.id=u.taller_id
                 WHERE u.id=? AND u.taller_id=? LIMIT 2
                """, (row, index) -> row.getLong("taller_id") == actor.tallerId()
                && actor.role().name().equals(row.getString("role")) && row.getBoolean("active")
                && row.getBoolean("activo") && row.getLong("token_version") == actor.tokenVersion(),
                actor.userId(), actor.tallerId());
        if (rows.size() != 1 || !Boolean.TRUE.equals(rows.getFirst())) throw new LegalActorSnapshotException();
    }

    private static LegalActorSnapshot identify(AuthenticatedUserPrincipal principal) {
        if (principal == null || principal.getUserId() == null || principal.getUserId() <= 0
                || principal.getTallerId() == null || principal.getTallerId() <= 0
                || principal.getTokenVersion() < 0 || !principal.isEnabled()
                || !principal.isAccountNonExpired() || !principal.isAccountNonLocked()
                || !principal.isCredentialsNonExpired() || principal.getAuthorities().size() != 1) {
            throw new LegalActorSnapshotException();
        }
        UserRole role = switch (principal.getAuthorities().iterator().next().getAuthority()) {
            case "ROLE_ADMIN" -> UserRole.ADMIN;
            case "ROLE_USER" -> UserRole.USER;
            default -> throw new LegalActorSnapshotException();
        };
        return new LegalActorSnapshot(principal.getUserId(), principal.getTallerId(), role,
                principal.getTokenVersion(), true, true);
    }

    private static LegalAcceptanceFailure.Reason classify(RuntimeException failure) {
        if (failure instanceof LegalAcceptanceFailure typed) return typed.reason();
        if (failure instanceof LegalActorSnapshotException) return LegalAcceptanceFailure.Reason.INVALID_ACTOR;
        if (failure instanceof LegalAcceptanceValidationException typed) return typed.reason()
                == LegalAcceptanceValidationException.Reason.STALE
                ? LegalAcceptanceFailure.Reason.STALE : LegalAcceptanceFailure.Reason.INVALID;
        if (failure instanceof LegalIdempotencyException typed) return switch (typed.reason()) {
            case INVALID_ACTOR -> LegalAcceptanceFailure.Reason.INVALID_ACTOR;
            case KEY_REUSED -> LegalAcceptanceFailure.Reason.KEY_REUSED;
            case IN_PROGRESS -> LegalAcceptanceFailure.Reason.IN_PROGRESS;
            case UNAVAILABLE -> LegalAcceptanceFailure.Reason.UNAVAILABLE;
        };
        return LegalAcceptanceFailure.Reason.UNAVAILABLE;
    }
}
