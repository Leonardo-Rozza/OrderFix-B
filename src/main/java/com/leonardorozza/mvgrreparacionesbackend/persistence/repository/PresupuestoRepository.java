package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Presupuesto;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoPresupuesto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PresupuestoRepository extends JpaRepository<Presupuesto, Long> {

    List<Presupuesto> findByReparacionIdAndTallerIdOrderByCreatedAtDesc(Long reparacionId, Long tallerId);

    Optional<Presupuesto> findFirstByReparacionIdOrderByCreatedAtDesc(Long reparacionId);

    Optional<Presupuesto> findFirstByReparacionIdAndEstadoOrderByCreatedAtDesc(
            Long reparacionId, EstadoPresupuesto estado);
}
