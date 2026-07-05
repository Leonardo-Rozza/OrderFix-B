package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Token de un solo uso enviado por email (reset de contraseña / verificación de email).
 * Se guarda solo el hash SHA-256 del token: si roban la DB, no pueden usar los links.
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoAuthToken tipo;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "usado_en")
    private LocalDateTime usadoEn;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public boolean isUsable() {
        return usadoEn == null && expiraEn != null && LocalDateTime.now().isBefore(expiraEn);
    }
}
