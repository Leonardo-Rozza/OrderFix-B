package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalRequisitoLinea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LegalRequisitoLineaRepository extends JpaRepository<LegalRequisitoLinea, UUID> {
    Optional<LegalRequisitoLinea> findByClaveAndLocale(String clave, LocaleLegal locale);

    List<LegalRequisitoLinea> findAllByLocaleAndContexto(LocaleLegal locale, ContextoLegal contexto);
}
