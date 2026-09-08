package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalApplicableScopeResolver;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Internal complete registration only: one operation budget and transaction, no HTTP or session effects. */
public final class LegalRegistrationService {
    private final JdbcTemplate jdbc;
    private final LegalPrivateRequirementsDataSource dataSource;
    private final LegalRegistrationTransactionBoundary boundary;
    private final LegalApplicableScopeResolver resolver;
    private final LegalRequiredSetAggregateStore aggregates;
    private final LegalPublicRequirementsReader requirements;
    private final LegalRegistrationPreparation preparation;
    private final LegalRegistrationWriter writer;
    private final LegalIdempotencyCoordinator coordinator;
    private final LegalRegistrationSelection selection = new LegalRegistrationSelection();

    LegalRegistrationService(JdbcTemplate jdbc, LegalPrivateRequirementsDataSource dataSource,
            LegalRegistrationTransactionBoundary boundary, LegalApplicableScopeResolver resolver,
            LegalRequiredSetAggregateStore aggregates, LegalPublicRequirementsReader requirements,
            LegalRegistrationPreparation preparation, LegalRegistrationWriter writer,
            LegalV29AcceptanceSchemaVerifier schema, LegalAcceptanceKeyConfiguration keys) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.writer = Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(keys, "keys");
        boundary.requireExactBoundary();
        if (!dataSource.isRegistrationBoundary() || jdbc.getDataSource() != dataSource
                || !boundary.usesJdbc(jdbc) || !aggregates.usesJdbc(jdbc) || !requirements.usesJdbc(jdbc)
                || !writer.usesJdbc(jdbc) || !schema.usesJdbc(jdbc)) {
            throw new IllegalArgumentException("El registro requiere colaboradores en una única frontera JDBC");
        }
        coordinator = new LegalIdempotencyCoordinator(jdbc, schema, keys.keyring(), new LegalIdempotencyResultStore(jdbc));
    }

    /** The caller classifies legacy/partial requests and supplies metadata captured by the server. */
    public LegalRegistrationReceipt register(Registration registration, String idempotencyKey,
            String requiredSetRevision, List<Acceptance> acceptances, LegalRequestMetadata metadata) {
        return register(registration, idempotencyKey, requiredSetRevision, acceptances, metadata, null, false);
    }

    /** Continue the caller's original registration budget, including time spent before this service. */
    public LegalRegistrationReceipt register(Registration registration, String idempotencyKey,
            String requiredSetRevision, List<Acceptance> acceptances, LegalRequestMetadata metadata,
            LegalRegistrationBudget owner) {
        return register(registration, idempotencyKey, requiredSetRevision, acceptances, metadata, owner, true);
    }

    private LegalRegistrationReceipt register(Registration registration, String idempotencyKey,
            String requiredSetRevision, List<Acceptance> acceptances, LegalRequestMetadata metadata,
            LegalRegistrationBudget owner, boolean suppliedOwner) {
        var completion = new LegalTransactionCompletionState<LegalRegistrationReceipt>();
        var verifiedReplay = new AtomicReference<LegalRegistrationReceipt>();
        try {
            Function<LegalPrivateRequirementsDeadline, LegalRegistrationReceipt> operation = deadline -> {
                try {
                    final LegalAcceptanceCommand command;
                    try {
                        LegalAcceptanceCommandValidator.requireIdempotencyKey(idempotencyKey);
                        command = LegalAcceptanceCommandValidator.registration(registration, requiredSetRevision, acceptances);
                    } catch (IllegalArgumentException invalidShape) {
                        throw new LegalRegistrationFailure(LegalRegistrationFailure.Reason.INVALID_PAYLOAD,
                                completion.snapshot(), null, invalidShape);
                    }
                    deadline.check();
                    if (metadata == null) throw new IllegalStateException("La captura legal requerida no está disponible");
                    return boundary.execute(completion, (status, transactionDeadline) -> {
                        if (transactionDeadline != deadline) {
                            throw new IllegalStateException("El registro requiere un presupuesto compartido");
                        }
                        var reservation = coordinator.reserve(command, idempotencyKey, deadline::remainingMillis);
                        var replay = reservation.replay();
                        if (replay.isPresent()) {
                            var stored = replay.get();
                            if (stored.source() != LegalIdempotencyResultStore.Source.WITH_ACTS) {
                                throw new IllegalStateException("El resultado de registro no es coherente");
                            }
                            var receipt = new LegalRegistrationReceipt(stored.userId(), stored.tallerId(), true,
                                    stored.lotId(), stored.acceptanceIds());
                            // This evidence belongs to a previous committed operation. A later rollback
                            // of the delivery transaction cannot erase that durable identity.
                            verifiedReplay.set(receipt);
                            deadline.check();
                            return receipt;
                        }
                        var editorial = boundary.enterEditorialShared(reservation, deadline);
                        var scopes = resolver.resolve(PerfilAgregadoLegal.REGISTRATION,
                                LocaleLegal.ES_AR, AudienciaLegal.ADMIN_TITULAR);
                        deadline.check();
                        var aggregate = aggregates.materialize(scopes, editorial);
                        var current = requirements.readForRegistration(aggregate, editorial, deadline);
                        deadline.check();
                        var selected = selection.select(command, current);
                        var prepared = preparation.prepare(command, deadline);
                        var receipt = Objects.requireNonNull(writer.write(reservation, prepared, aggregate, selected, metadata));
                        if (receipt.replay()) throw new IllegalStateException("El escritor de registro devolvió un replay");
                        reservation.readBudget();
                        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                        deadline.check();
                        return receipt;
                    });
                } catch (RuntimeException failure) {
                    // The datasource checks successful returns. Also accredit exceptional work
                    // before the transaction, where shape validation may have spent the budget.
                    try {
                        deadline.check();
                    } catch (RuntimeException boundaryFailure) {
                        if (boundaryFailure != failure) boundaryFailure.addSuppressed(failure);
                        throw boundaryFailure;
                    }
                    throw failure;
                }
            };
            return suppliedOwner ? dataSource.withinRegistrationBudget(owner, operation)
                    : dataSource.withinDeadline(operation);
        } catch (RuntimeException failure) {
            LegalRegistrationFailure.Reason reason = classify(failure);
            RuntimeException cause = failure instanceof LegalRegistrationFailure typed
                    && typed.getCause() instanceof RuntimeException inner ? inner : failure;
            throw new LegalRegistrationFailure(reason, completion.snapshot(), verifiedReplay.get(), cause);
        }
    }

    private static LegalRegistrationFailure.Reason classify(RuntimeException failure) {
        if (failure instanceof LegalRegistrationFailure typed) return typed.reason();
        if (failure instanceof LegalRegistrationValidationException typed) {
            return typed.reason() == LegalRegistrationValidationException.Reason.STALE
                    ? LegalRegistrationFailure.Reason.STALE : LegalRegistrationFailure.Reason.INVALID;
        }
        if (failure instanceof LegalIdempotencyException typed) return switch (typed.reason()) {
            case INVALID_ACTOR -> LegalRegistrationFailure.Reason.INVALID_ACTOR;
            case KEY_REUSED -> LegalRegistrationFailure.Reason.KEY_REUSED;
            case IN_PROGRESS -> LegalRegistrationFailure.Reason.IN_PROGRESS;
            case UNAVAILABLE -> LegalRegistrationFailure.Reason.UNAVAILABLE;
        };
        return LegalRegistrationFailure.Reason.UNAVAILABLE;
    }
}
