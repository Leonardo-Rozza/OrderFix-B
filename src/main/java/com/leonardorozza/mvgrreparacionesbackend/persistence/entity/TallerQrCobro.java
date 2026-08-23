package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "taller_qr_cobro")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TallerQrCobro {

    @Id
    @Column(name = "taller_id")
    private Long tallerId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    private Taller taller;

    /** Se fuerza VARBINARY para que PostgreSQL use BYTEA y no un OID de objeto grande. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "png", nullable = false, length = 1_048_576)
    private byte[] png;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;
}
