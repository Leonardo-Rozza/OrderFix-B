package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoConstruccionLegal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_documento_reemplazo_lotes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocumentoReemplazoLote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_construccion", nullable = false, length = 10)
    private EstadoConstruccionLegal estadoConstruccion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "sellado_en")
    private Instant selladoEn;

    public LegalDocumentoReemplazoLote(Instant creadoEn) {
        this.estadoConstruccion = EstadoConstruccionLegal.ABIERTO;
        this.creadoEn = Objects.requireNonNull(creadoEn);
    }

    public void sellar(Instant instante) {
        if (estadoConstruccion != EstadoConstruccionLegal.ABIERTO) {
            throw new IllegalStateException("El lote de reemplazo ya está sellado");
        }
        this.estadoConstruccion = EstadoConstruccionLegal.SELLADO;
        this.selladoEn = Objects.requireNonNull(instante);
    }
}
