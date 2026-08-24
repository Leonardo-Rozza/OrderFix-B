package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalPublicacionRepository extends JpaRepository<LegalPublicacion, UUID> {
    Optional<LegalPublicacion> findByPublicationExternalId(String publicationExternalId);

    List<LegalPublicacion> findAllByLocaleAndEstadoConstruccionOrderByImportadoEnDesc(
            LocaleLegal locale, EstadoConstruccionLegal estadoConstruccion);
}
