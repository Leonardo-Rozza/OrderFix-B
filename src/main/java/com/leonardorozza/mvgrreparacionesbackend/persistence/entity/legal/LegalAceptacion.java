package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.legal;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
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
import java.util.UUID;

@Entity
@Table(name = "legal_aceptaciones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_legal_aceptacion_actor_version", columnNames = {"user_id", "requisito_version_id"}),
        @UniqueConstraint(name = "uk_legal_aceptacion_actor", columnNames = {"id", "user_id", "taller_id"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalAceptacion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lote_id", nullable = false, updatable = false)
    private UUID loteId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "taller_id", nullable = false, updatable = false)
    private Long tallerId;

    @Column(name = "requisito_version_id", nullable = false, updatable = false)
    private UUID requisitoVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false, insertable = false, updatable = false)
    private LegalAceptacionLote lote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisito_version_id", nullable = false, insertable = false, updatable = false)
    private LegalRequisitoVersion requisitoVersion;

    @Column(name = "requisito_clave", nullable = false, updatable = false, length = 120)
    private String requisitoClave;

    @Column(name = "requisito_version", nullable = false, updatable = false, length = 64)
    private String requisitoVersionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "contexto", nullable = false, updatable = false, length = 40)
    private ContextoLegal contexto;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_acto", nullable = false, updatable = false, length = 20)
    private TipoActoLegal tipoActo;

    @Column(name = "afirmacion", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String afirmacion;

    @Column(name = "afirmacion_sha256", nullable = false, updatable = false, length = 64)
    private String afirmacionSha256;

    @Column(name = "requerido", nullable = false, updatable = false)
    private boolean requerido;

    public LegalAceptacion(UUID loteId, Long userId, Long tallerId, UUID requisitoVersionId,
                           String requisitoClave, String requisitoVersionSnapshot,
                           ContextoLegal contexto, TipoActoLegal tipoActo,
                           String afirmacion, String afirmacionSha256, boolean requerido) {
        this.loteId = Objects.requireNonNull(loteId);
        this.userId = Objects.requireNonNull(userId);
        this.tallerId = Objects.requireNonNull(tallerId);
        this.requisitoVersionId = Objects.requireNonNull(requisitoVersionId);
        this.requisitoClave = Objects.requireNonNull(requisitoClave);
        this.requisitoVersionSnapshot = Objects.requireNonNull(requisitoVersionSnapshot);
        this.contexto = Objects.requireNonNull(contexto);
        this.tipoActo = Objects.requireNonNull(tipoActo);
        this.afirmacion = Objects.requireNonNull(afirmacion);
        this.afirmacionSha256 = Objects.requireNonNull(afirmacionSha256);
        this.requerido = requerido;
    }
}
