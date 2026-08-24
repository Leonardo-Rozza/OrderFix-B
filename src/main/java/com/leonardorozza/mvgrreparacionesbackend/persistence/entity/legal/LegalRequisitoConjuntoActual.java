package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal.id.LegalRequisitoConjuntoActualId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_requisito_conjuntos_actuales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoConjuntoActual {

    @EmbeddedId
    private LegalRequisitoConjuntoActualId id;

    @Column(name = "conjunto_id", nullable = false, updatable = false)
    private UUID conjuntoId;

    @Column(name = "publicacion_id", nullable = false, updatable = false)
    private UUID publicacionId;

    @Column(name = "actualizado_en", nullable = false, updatable = false)
    private Instant actualizadoEn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conjunto_id", nullable = false, insertable = false, updatable = false)
    private LegalRequisitoConjunto conjunto;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publicacion_id", nullable = false, insertable = false, updatable = false)
    private LegalPublicacion publicacion;

    public LegalRequisitoConjuntoActual(LegalRequisitoConjuntoActualId id,
                                        UUID conjuntoId, UUID publicacionId,
                                        Instant actualizadoEn) {
        this.id = Objects.requireNonNull(id);
        this.conjuntoId = Objects.requireNonNull(conjuntoId);
        this.publicacionId = Objects.requireNonNull(publicacionId);
        this.actualizadoEn = Objects.requireNonNull(actualizadoEn);
    }
}
