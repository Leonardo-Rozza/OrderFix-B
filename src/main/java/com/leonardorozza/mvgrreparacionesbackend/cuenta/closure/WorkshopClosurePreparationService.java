package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.ClosurePreparationException.Code.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.ClosureRenewalAssessment.*;

/** Internal read preparation. No endpoint, grants, mutation, cleanup or provider calls. */
@Service
public final class WorkshopClosurePreparationService {
    private static final int MAX_LINKS = 1000;
    private final JdbcTemplate jdbc;
    private final ExportReauthenticationService authorization;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WorkshopClosurePreparationService(JdbcTemplate jdbc, ExportReauthenticationService authorization,
            PlatformTransactionManager manager, Clock clock) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.clock = java.util.Objects.requireNonNull(clock);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // The existing verified-identity reader requires a writable transaction for row locks.
        // This service performs no INSERT, UPDATE or DELETE and never issues/consumes a proof.
        transaction.setTimeout(10);
    }

    public WorkshopClosurePreparation prepare(String accessToken) {
        try {
            return transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '5s'");
                var actor = authorization.authorize(accessToken);
                var observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
                String name = jdbc.queryForObject(
                        "SELECT nombre FROM public.talleres WHERE id=? AND activo IS TRUE FOR SHARE",
                        String.class, actor.tallerId());
                if (name == null || name.isBlank() || name.length() > 255) throw rejected(SOURCE_INVALID);

                var workforce = jdbc.queryForObject("""
                        SELECT count(*) FILTER (WHERE role='USER' AND active IS TRUE) AS active_employees,
                               count(*) FILTER (WHERE role='USER' AND active IS FALSE) AS inactive_employees,
                               count(*) FILTER (WHERE role IS NULL OR role NOT IN ('ADMIN','USER') OR active IS NULL) AS invalid
                          FROM public.users WHERE taller_id=?
                        """, (rs, row) -> {
                    if (rs.getLong("invalid") != 0) throw rejected(SOURCE_INVALID);
                    return new WorkshopClosurePreparation.Workforce(rs.getLong("active_employees"),
                            rs.getLong("inactive_employees"));
                }, actor.tallerId());

                var resources = jdbc.queryForObject("""
                        SELECT
                         (SELECT count(*) FROM public.reparacion_fotos_privadas WHERE taller_id=?) AS photos,
                         (SELECT count(*) FROM public.reparacion_fotos_privadas WHERE taller_id=? AND lease_id IS NOT NULL) AS leases,
                         (SELECT count(*) FROM public.reparacion_fotos_privadas WHERE taller_id=?
                            AND estado IN ('EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE')) AS cleanup,
                         (SELECT count(*) FROM public.cuenta_exportaciones WHERE taller_id=?
                            AND estado IN ('QUEUED','RUNNING')) AS pending_exports,
                         (SELECT count(*) FROM public.cuenta_exportaciones WHERE taller_id=? AND estado='READY') AS ready_exports
                        """, (rs, row) -> new WorkshopClosurePreparation.Resources(rs.getLong("photos"),
                                rs.getLong("leases"), rs.getLong("cleanup"), rs.getLong("pending_exports"),
                                rs.getLong("ready_exports")),
                        actor.tallerId(), actor.tallerId(), actor.tallerId(), actor.tallerId(), actor.tallerId());

                List<SubscriptionEvidence> subscriptions = jdbc.query("""
                        SELECT CASE WHEN plan IN ('FREE','PRO') THEN plan ELSE 'UNKNOWN' END AS plan,
                               CASE lower(btrim(coalesce(mp_status,'')))
                                 WHEN '' THEN 'NONE' WHEN 'authorized' THEN 'AUTHORIZED'
                                 WHEN 'pending' THEN 'PENDING' WHEN 'paused' THEN 'PAUSED'
                                 WHEN 'canceled' THEN 'CANCELED' WHEN 'cancelled' THEN 'CANCELED'
                                 WHEN 'creating' THEN 'CREATING' WHEN 'retryable' THEN 'RETRYABLE'
                                 ELSE 'UNKNOWN' END AS state,
                               nullif(btrim(mp_preapproval_id),'') IS NOT NULL AS external_id,
                               (nullif(btrim(mp_payer_id),'') IS NOT NULL
                                OR nullif(btrim(mp_external_reference),'') IS NOT NULL
                                OR nullif(btrim(mp_checkout_init_point),'') IS NOT NULL
                                OR nullif(btrim(mp_last_authorized_payment_id),'') IS NOT NULL
                                OR mp_next_payment_at IS NOT NULL OR mp_last_payment_at IS NOT NULL
                                OR proximo_cobro IS NOT NULL) AS other_evidence
                          FROM public.suscripciones WHERE taller_id=? LIMIT 2
                        """, (rs, row) -> new SubscriptionEvidence(true, Plan.valueOf(rs.getString("plan")),
                                ProviderState.valueOf(rs.getString("state")), rs.getBoolean("external_id"),
                                rs.getBoolean("other_evidence")), actor.tallerId());
                if (subscriptions.size() > 1) throw rejected(SOURCE_INVALID);
                SubscriptionEvidence subscription = subscriptions.isEmpty()
                        ? new SubscriptionEvidence(false, Plan.UNKNOWN, ProviderState.NONE, false, false)
                        : subscriptions.getFirst();

                List<LinkEvidence> links = jdbc.query("""
                        SELECT l.is_current,
                               CASE WHEN l.provider<>'MERCADO_PAGO' OR (l.is_current AND (
                                 ((CASE WHEN nullif(btrim(l.external_subscription_id),'') IS NULL THEN NULL ELSE l.external_subscription_id END)
                                   IS DISTINCT FROM
                                  (CASE WHEN nullif(btrim(s.mp_preapproval_id),'') IS NULL THEN NULL ELSE s.mp_preapproval_id END))
                                 OR (replace(lower(nullif(btrim(l.status),'')),'cancelled','canceled')
                                   IS DISTINCT FROM replace(lower(nullif(btrim(s.mp_status),'')),'cancelled','canceled'))
                               )) THEN 'UNKNOWN'
                                 ELSE CASE lower(btrim(coalesce(l.status,'')))
                                   WHEN '' THEN 'NONE' WHEN 'authorized' THEN 'AUTHORIZED'
                                   WHEN 'pending' THEN 'PENDING' WHEN 'paused' THEN 'PAUSED'
                                   WHEN 'canceled' THEN 'CANCELED' WHEN 'cancelled' THEN 'CANCELED'
                                   WHEN 'creating' THEN 'CREATING' WHEN 'retryable' THEN 'RETRYABLE'
                                   ELSE 'UNKNOWN' END END AS state,
                               nullif(btrim(l.external_subscription_id),'') IS NOT NULL AS external_id,
                               (nullif(btrim(l.external_reference),'') IS NOT NULL
                                OR nullif(btrim(l.idempotency_key),'') IS NOT NULL
                                OR nullif(btrim(l.checkout_url),'') IS NOT NULL) AS other_evidence
                          FROM public.subscription_provider_links l
                          JOIN public.suscripciones s ON s.id=l.suscripcion_id
                         WHERE s.taller_id=? ORDER BY l.id LIMIT 1001
                        """, (rs, row) -> new LinkEvidence(rs.getBoolean("is_current"),
                                ProviderState.valueOf(rs.getString("state")), rs.getBoolean("external_id"),
                                rs.getBoolean("other_evidence")), actor.tallerId());
                if (links.size() > MAX_LINKS) throw rejected(CAPACITY_EXCEEDED);
                var renewal = assess(subscription, links);
                // A long read does not extend the JWT lifetime or authorize a changed identity.
                if (!actor.equals(authorization.authorize(accessToken))) throw rejected(SESSION_INVALID);
                return new WorkshopClosurePreparation(actor.userId(), actor.tallerId(), actor.tokenVersion(),
                        name, observedAt, workforce, resources, renewal);
            });
        } catch (ClosurePreparationException rejected) {
            throw rejected;
        } catch (UnauthorizedException rejected) {
            throw rejected(SESSION_INVALID);
        } catch (AccessDeniedException rejected) {
            throw rejected(FORBIDDEN);
        } catch (RuntimeException unavailable) {
            throw rejected(UNAVAILABLE);
        }
    }
    private static ClosurePreparationException rejected(ClosurePreparationException.Code code) {
        return new ClosurePreparationException(code);
    }
}
