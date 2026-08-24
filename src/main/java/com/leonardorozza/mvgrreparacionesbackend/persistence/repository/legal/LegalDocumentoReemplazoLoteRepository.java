package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalDocumentoReemplazoLote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LegalDocumentoReemplazoLoteRepository
        extends JpaRepository<LegalDocumentoReemplazoLote, UUID> {
}
