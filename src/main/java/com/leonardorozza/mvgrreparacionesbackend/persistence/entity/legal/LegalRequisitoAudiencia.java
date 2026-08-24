package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.Objects;

@Entity
@Table(name = "legal_requisito_audiencias", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_req_audiencia", columnNames = {"requisito_linea_id", "audiencia"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRequisitoAudiencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_linea_id", nullable = false, updatable = false)
    private LegalRequisitoLinea requisitoLinea;

    @Enumerated(EnumType.STRING)
    @Column(name = "audiencia", nullable = false, updatable = false, length = 20)
    private AudienciaLegal audiencia;

    public LegalRequisitoAudiencia(LegalRequisitoLinea requisitoLinea, AudienciaLegal audiencia) {
        this.requisitoLinea = Objects.requireNonNull(requisitoLinea);
        this.audiencia = Objects.requireNonNull(audiencia);
    }
}
