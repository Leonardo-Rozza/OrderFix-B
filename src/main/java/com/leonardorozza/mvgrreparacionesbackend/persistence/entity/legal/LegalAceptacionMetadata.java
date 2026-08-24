package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_aceptacion_metadatos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalAceptacionMetadata {

    @Id
    @Column(name = "lote_id", nullable = false, updatable = false)
    private UUID loteId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false, updatable = false)
    private LegalAceptacionLote lote;

    @Column(name = "capturado_en", nullable = false, updatable = false)
    @Generated(event = EventType.INSERT, writable = true)
    private Instant capturadoEn;

    @Column(name = "retener_hasta", nullable = false, updatable = false)
    private Instant retenerHasta;

    @Column(name = "purgado_en")
    private Instant purgadoEn;

    public LegalAceptacionMetadata(LegalAceptacionLote lote, Instant capturadoEn,
                                   Instant retenerHasta) {
        this.lote = Objects.requireNonNull(lote);
        this.capturadoEn = Objects.requireNonNull(capturadoEn);
        this.retenerHasta = Objects.requireNonNull(retenerHasta);
    }

    public void marcarPurgado(Instant instante) {
        if (purgadoEn != null) {
            throw new IllegalStateException("La metadata ya fue purgada");
        }
        this.purgadoEn = Objects.requireNonNull(instante);
    }
}
