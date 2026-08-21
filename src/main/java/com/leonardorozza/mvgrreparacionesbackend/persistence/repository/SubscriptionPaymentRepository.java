package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.SubscriptionPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    Optional<SubscriptionPayment> findByProviderAndExternalAuthorizedPaymentId(
            String provider, String externalAuthorizedPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from SubscriptionPayment p
            where p.provider = :provider
              and p.externalAuthorizedPaymentId = :externalAuthorizedPaymentId
            """)
    Optional<SubscriptionPayment> findByProviderAndExternalAuthorizedPaymentIdForUpdate(
            @Param("provider") String provider,
            @Param("externalAuthorizedPaymentId") String externalAuthorizedPaymentId);
}
