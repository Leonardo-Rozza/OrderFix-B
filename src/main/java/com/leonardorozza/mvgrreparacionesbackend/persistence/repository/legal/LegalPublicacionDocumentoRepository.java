package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalPublicacionDocumento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalPublicacionDocumentoRepository extends JpaRepository<LegalPublicacionDocumento, Long> {
    Optional<LegalPublicacionDocumento> findByPublicacion_IdAndDocumentoVersion_Id(
            UUID publicacionId, UUID documentoVersionId);

    List<LegalPublicacionDocumento> findAllByPublicacion_IdOrderByManifestOrdinalAsc(UUID publicacionId);
}
