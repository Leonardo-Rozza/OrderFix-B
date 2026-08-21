package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SuscripcionRepository extends JpaRepository<Suscripcion, Long> {

    Optional<Suscripcion> findByTallerId(Long tallerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Suscripcion s join fetch s.taller where s.taller.id = :tallerId")
    Optional<Suscripcion> findByTallerIdForUpdate(@Param("tallerId") Long tallerId);

    Optional<Suscripcion> findByMpPreapprovalId(String mpPreapprovalId);

    List<Suscripcion> findByEstadoAndFechaFinTrialBefore(EstadoSuscripcion estado, LocalDate fecha);
}
