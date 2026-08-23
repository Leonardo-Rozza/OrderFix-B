package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Tenant del sistema: cada taller de reparación es una cuenta aislada.
 */
@Entity
@Table(name = "talleres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Taller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 120)
    private String emailContacto;

    @Column(length = 20)
    private String telefono;

    @Column(name = "alias_cobro", length = 120)
    private String aliasCobro;

    @Column(name = "titular_cobro", length = 160)
    private String titularCobro;

    @Column(name = "entidad_cobro", length = 120)
    private String entidadCobro;

    @Column(name = "mostrar_en_resumen", nullable = false)
    @Builder.Default
    private boolean mostrarEnResumen = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean activo = true;

    // ----- Numeración de órdenes (correlativo por taller con reinicio anual) -----
    /** Último correlativo de orden usado en el año {@code anioSecuenciaOrden}. */
    @Column(name = "secuencia_orden", nullable = false)
    @Builder.Default
    private int secuenciaOrden = 0;

    /** Año al que corresponde {@code secuenciaOrden} (al cambiar de año, reinicia). */
    @Column(name = "anio_secuencia_orden")
    private Integer anioSecuenciaOrden;

    @OneToOne(mappedBy = "taller", cascade = CascadeType.ALL, orphanRemoval = true)
    private Suscripcion suscripcion;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
