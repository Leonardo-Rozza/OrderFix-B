package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EsquemaRevisionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_aceptacion_lotes", uniqueConstraints =
        @UniqueConstraint(name = "uk_legal_lote_actor", columnNames = {"id", "user_id", "taller_id"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalAceptacionLote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "taller_id", nullable = false, updatable = false)
    private Long tallerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false, insertable = false, updatable = false)
    private Taller taller;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol_wire", nullable = false, updatable = false, length = 10)
    private UserRole rolWire;

    @Enumerated(EnumType.STRING)
    @Column(name = "audiencia", nullable = false, updatable = false, length = 20)
    private AudienciaLegal audiencia;

    @Column(name = "required_set_revision", nullable = false, updatable = false, length = 71)
    private String requiredSetRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_scheme", nullable = false, updatable = false, length = 20)
    private EsquemaRevisionLegal revisionScheme;

    @Enumerated(EnumType.STRING)
    @Column(name = "perfil", updatable = false, length = 30)
    private PerfilAgregadoLegal perfil;

    @Column(name = "agregado_id", updatable = false)
    private UUID agregadoId;

    @Column(name = "aceptado_en", nullable = false, updatable = false)
    @Generated(event = EventType.INSERT, writable = true)
    private Instant aceptadoEn;

    public LegalAceptacionLote(Long userId, Long tallerId, UserRole rolWire,
                               PerfilAgregadoLegal perfil, UUID agregadoId,
                               String requiredSetRevision, Instant aceptadoEn) {
        this.userId = Objects.requireNonNull(userId);
        this.tallerId = Objects.requireNonNull(tallerId);
        this.rolWire = Objects.requireNonNull(rolWire);
        this.audiencia = rolWire.toAudienciaLegal();
        this.revisionScheme = EsquemaRevisionLegal.AGGREGATE_V1;
        this.perfil = Objects.requireNonNull(perfil);
        this.agregadoId = Objects.requireNonNull(agregadoId);
        this.requiredSetRevision = Objects.requireNonNull(requiredSetRevision);
        this.aceptadoEn = Objects.requireNonNull(aceptadoEn);
    }
}
