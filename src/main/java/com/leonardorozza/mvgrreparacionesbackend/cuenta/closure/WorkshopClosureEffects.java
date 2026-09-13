package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable intentions only. Called inside the command transaction, after its operation and closure exist. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class WorkshopClosureEffects {
    private final JdbcTemplate jdbc;
    public WorkshopClosureEffects(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    public void enqueueClose(UUID operationId, UUID closureReference, long tallerId, long userId, Instant now) {
        requireInput(operationId,closureReference,tallerId,userId,now);
        List<Link> links=jdbc.query("""
                SELECT l.id,l.provider,l.external_reference,l.external_subscription_id
                  FROM public.subscription_provider_links l JOIN public.suscripciones s ON s.id=l.suscripcion_id
                 WHERE s.taller_id=? ORDER BY l.id LIMIT 1001
                """,(rs,row)->new Link(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),tallerId);
        if(links.size()>1000) throw new ClosurePreparationException(ClosurePreparationException.Code.CAPACITY_EXCEEDED);
        boolean uncertain=false;
        for(Link link:links) {
            if(!"MERCADO_PAGO".equals(link.provider()) || !text(link.reference())) { uncertain=true; continue; }
            jdbc.update("""
                    INSERT INTO public.cuenta_cierre_efectos(efecto_id,operacion_id,cierre_referencia,taller_id,usuario_id,tipo,
                        link_id,expected_external_reference,expected_external_id,estado,available_at,created_at)
                    VALUES(?,?,?,?,?,'CANCELAR_RENOVACION',?,?,?,'PENDIENTE',?,?)
                    ON CONFLICT(link_id) WHERE tipo='CANCELAR_RENOVACION' DO NOTHING
                    """,UUID.randomUUID(),operationId,closureReference,tallerId,userId,link.id(),link.reference(),
                    text(link.externalId())?link.externalId():null,Timestamp.from(now),Timestamp.from(now));
        }
        // FREE, feature flags and local canceled status do not erase unrepresented provider evidence.
        Boolean incomplete=jdbc.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM public.suscripciones WHERE taller_id=?) OR EXISTS(SELECT 1 FROM public.suscripciones s WHERE s.taller_id=? AND (
                    ((s.plan='PRO' OR nullif(btrim(s.mp_status),'') IS NOT NULL
                      OR nullif(btrim(s.mp_preapproval_id),'') IS NOT NULL OR nullif(btrim(s.mp_external_reference),'') IS NOT NULL
                      OR nullif(btrim(s.mp_payer_id),'') IS NOT NULL OR nullif(btrim(s.mp_checkout_init_point),'') IS NOT NULL
                      OR nullif(btrim(s.mp_last_authorized_payment_id),'') IS NOT NULL
                      OR s.mp_next_payment_at IS NOT NULL OR s.mp_last_payment_at IS NOT NULL OR s.proximo_cobro IS NOT NULL)
                     AND NOT EXISTS(SELECT 1 FROM public.subscription_provider_links l WHERE l.suscripcion_id=s.id AND l.is_current))
                    OR (SELECT count(*) FROM public.subscription_provider_links l WHERE l.suscripcion_id=s.id AND l.is_current)>1
                    OR EXISTS(SELECT 1 FROM public.subscription_provider_links l WHERE l.suscripcion_id=s.id AND l.is_current AND (
                       l.provider<>'MERCADO_PAGO' OR l.external_subscription_id IS DISTINCT FROM s.mp_preapproval_id
                       OR l.external_reference IS DISTINCT FROM s.mp_external_reference)) ))
                """,Boolean.class,tallerId,tallerId);
        if(uncertain || Boolean.TRUE.equals(incomplete)) enqueue(operationId,closureReference,tallerId,userId,now,"REVISAR_RENOVACION","INCIERTO");
        enqueue(operationId,closureReference,tallerId,userId,now,"AVISO_CIERRE","PENDIENTE");
    }

    public void enqueueRestore(UUID operationId, UUID closureReference, long tallerId, long userId, Instant now) {
        requireInput(operationId,closureReference,tallerId,userId,now);
        enqueue(operationId,closureReference,tallerId,userId,now,"AVISO_RESTAURACION","PENDIENTE");
    }

    private void enqueue(UUID operation,UUID reference,long taller,long user,Instant now,String type,String state) {
        jdbc.update("""
                INSERT INTO public.cuenta_cierre_efectos(efecto_id,operacion_id,cierre_referencia,taller_id,usuario_id,tipo,
                    estado,available_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(operacion_id,tipo) WHERE tipo<>'CANCELAR_RENOVACION' DO NOTHING
                """,UUID.randomUUID(),operation,reference,taller,user,type,state,Timestamp.from(now),Timestamp.from(now));
    }

    /** The mark survives restoration and confirmation; it does not claim anything about remote state. */
    public boolean blocksRenewal(long linkId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.subscription_provider_links l JOIN public.suscripciones s ON s.id=l.suscripcion_id
                  WHERE l.id=? AND EXISTS(SELECT 1 FROM public.cuenta_cierre_efectos e
                    WHERE (e.link_id=l.id AND e.tipo='CANCELAR_RENOVACION')
                       OR (e.taller_id=s.taller_id AND e.tipo='REVISAR_RENOVACION' AND e.estado='INCIERTO')))
                """,Boolean.class,linkId));
    }

    public boolean blocksWorkshopRenewal(long tallerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.cuenta_cierre_efectos
                  WHERE taller_id=? AND tipo='REVISAR_RENOVACION' AND estado='INCIERTO')
                """,Boolean.class,tallerId));
    }

    public boolean cancellationConfirmed(long linkId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.cuenta_cierre_efectos
                  WHERE link_id=? AND tipo='CANCELAR_RENOVACION' AND estado='CONFIRMADO')
                """,Boolean.class,linkId));
    }

    private static boolean text(String value) { return value!=null && !value.isBlank(); }
    private static void requireInput(UUID operation,UUID reference,long taller,long user,Instant now) {
        if(operation==null || reference==null || taller<=0 || user<=0 || now==null) throw new IllegalArgumentException("Invalid closure effect context");
    }
    private record Link(long id,String provider,String reference,String externalId) {
        @Override public String toString() { return "ClosureEffectLink[redacted]"; }
    }
}
