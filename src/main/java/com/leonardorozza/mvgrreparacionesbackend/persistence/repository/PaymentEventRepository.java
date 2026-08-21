package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.PaymentEvent;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PaymentEventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    Optional<PaymentEvent> findByProviderAndProviderEventKey(String provider, String providerEventKey);

    List<PaymentEvent> findTop50ByStatusAndAttemptsLessThanOrderByReceivedAtAsc(
            PaymentEventStatus status, int attempts);

    List<PaymentEvent> findTop50ByStatusAndLastAttemptAtBeforeOrderByLastAttemptAtAsc(
            PaymentEventStatus status, Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentEvent e where e.id = :id")
    Optional<PaymentEvent> findByIdForUpdate(@Param("id") Long id);
}
