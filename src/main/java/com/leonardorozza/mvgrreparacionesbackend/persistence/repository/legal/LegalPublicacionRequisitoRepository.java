package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalPublicacionRequisito;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalPublicacionRequisitoRepository extends JpaRepository<LegalPublicacionRequisito, Long> {
    Optional<LegalPublicacionRequisito> findByPublicacion_IdAndRequisitoVersion_Id(
            UUID publicacionId, UUID requisitoVersionId);

    List<LegalPublicacionRequisito> findAllByPublicacion_IdOrderByManifestOrdinalAsc(UUID publicacionId);
}
