package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SuscripcionRepository extends JpaRepository<Suscripcion, Long> {

    Optional<Suscripcion> findByTallerId(Long tallerId);

    Optional<Suscripcion> findByMpPreapprovalId(String mpPreapprovalId);

    List<Suscripcion> findByEstadoAndFechaFinTrialBefore(EstadoSuscripcion estado, LocalDate fecha);
}
