package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalAceptacionLote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalAceptacionLoteRepository extends JpaRepository<LegalAceptacionLote, UUID> {
    Optional<LegalAceptacionLote> findByIdAndUserIdAndTallerId(UUID id, Long userId, Long tallerId);
}
