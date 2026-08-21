package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.SubscriptionProviderLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionProviderLinkRepository extends JpaRepository<SubscriptionProviderLink, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SubscriptionProviderLink l where l.id = :id")
    Optional<SubscriptionProviderLink> findByIdForUpdate(@Param("id") Long id);

    Optional<SubscriptionProviderLink> findFirstBySuscripcionIdAndCurrentTrueOrderByIdDesc(Long suscripcionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SubscriptionProviderLink l
            join fetch l.suscripcion s
            join fetch s.taller
            where l.provider = 'MERCADO_PAGO' and l.externalSubscriptionId = :externalId
            """)
    Optional<SubscriptionProviderLink> findMercadoPagoByExternalId(@Param("externalId") String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from SubscriptionProviderLink l
            join fetch l.suscripcion s
            join fetch s.taller
            where l.provider = 'MERCADO_PAGO' and l.externalReference = :externalReference
            """)
    Optional<SubscriptionProviderLink> findMercadoPagoByExternalReference(
            @Param("externalReference") String externalReference);

    @Query("""
            select l.externalSubscriptionId
            from SubscriptionProviderLink l
            where l.provider = 'MERCADO_PAGO'
              and l.current = true
              and l.externalSubscriptionId is not null
            order by coalesce(l.lastReconciledAt, l.createdAt) asc
            """)
    List<String> findMercadoPagoReconciliationCandidates(Pageable pageable);
}
