package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoDocumentoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalDocumentoVigente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.id.LegalDocumentoVigenteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LegalDocumentoVigenteRepository
        extends JpaRepository<LegalDocumentoVigente, LegalDocumentoVigenteId> {

    Optional<LegalDocumentoVigente> findByIdTipoAndIdLocaleAndIdContexto(
            TipoDocumentoLegal tipo, LocaleLegal locale, ContextoLegal contexto);

}
