package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalDocumentoLinea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalDocumentoLineaRepository extends JpaRepository<LegalDocumentoLinea, UUID> {
    Optional<LegalDocumentoLinea> findByClaveAndLocale(String clave, LocaleLegal locale);
}
