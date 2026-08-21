package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nombre visible del usuario (ya no es el identificador de login)
    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private String password;  // Contraseña hasheada con BCrypt

    // Identificador de login: único global
    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;   // ADMIN / USER

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** Confirmó su email (verificación suave: sin confirmar puede operar igual). */
    @Column(name = "email_verificado", nullable = false)
    @Builder.Default
    private Boolean emailVerificado = false;

    /** Se incrementa al cambiar credenciales para revocar access tokens ya emitidos. */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

    // Tenant al que pertenece el usuario
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "taller_id")
    private Taller taller;

    /** Actualiza el hash y revoca atómicamente todos los access tokens anteriores. */
    public void cambiarPassword(String nuevoPasswordHash) {
        if (nuevoPasswordHash == null || nuevoPasswordHash.isBlank()) {
            throw new IllegalArgumentException("El hash de contraseña es obligatorio");
        }
        this.password = nuevoPasswordHash;
        this.tokenVersion = Math.addExact(this.tokenVersion, 1L);
    }
}
