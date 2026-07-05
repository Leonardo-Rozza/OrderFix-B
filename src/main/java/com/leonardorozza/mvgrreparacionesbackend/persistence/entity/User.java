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

    // Tenant al que pertenece el usuario
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "taller_id")
    private Taller taller;
}
