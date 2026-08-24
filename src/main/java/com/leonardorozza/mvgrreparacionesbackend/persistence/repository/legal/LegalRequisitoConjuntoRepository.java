package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoConjunto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalRequisitoConjuntoRepository extends JpaRepository<LegalRequisitoConjunto, UUID> {
    Optional<LegalRequisitoConjunto> findByPublicacion_IdAndLocaleAndContextoAndAudiencia(
            UUID publicacionId, LocaleLegal locale, ContextoLegal contexto, AudienciaLegal audiencia);
}
