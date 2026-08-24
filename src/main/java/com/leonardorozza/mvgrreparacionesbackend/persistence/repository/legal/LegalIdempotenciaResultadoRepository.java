package com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.LegalIdempotenciaResultado;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface LegalIdempotenciaResultadoRepository
        extends JpaRepository<LegalIdempotenciaResultado, Long> {

    @Query("""
            select r
              from LegalIdempotenciaResultado r
             where r.operacion = :operacion
               and r.routeTemplate = :routeTemplate
               and r.hmacKeyVersion = :hmacKeyVersion
               and r.scopeHmac = :scopeHmac
               and r.idempotencyKeyHmac = :idempotencyKeyHmac
               and r.expiresAt > :ahora
            """)
    Optional<LegalIdempotenciaResultado> findVigenteByTuplaHmac(
            @Param("operacion") TipoOperacionIdempotenteLegal operacion,
            @Param("routeTemplate") String routeTemplate,
            @Param("hmacKeyVersion") int hmacKeyVersion,
            @Param("scopeHmac") String scopeHmac,
            @Param("idempotencyKeyHmac") String idempotencyKeyHmac,
            @Param("ahora") Instant ahora);

    Page<LegalIdempotenciaResultado> findAllByExpiresAtLessThanEqualOrderByExpiresAtAsc(
            Instant instante, Pageable pageable);
}
