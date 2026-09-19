package com.leonardorozza.mvgrreparacionesbackend.persistence.entity;



import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EquipoTipo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "equipos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Equipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String marca;

    @Column(nullable = false, length = 60)
    private String modelo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EquipoTipo tipo = EquipoTipo.OTRO;

    @Column(length = 30)
    private String imei;

    @Column(length = 40)
    private String color;

    @Column(length = 255)
    private String descripcion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "taller_id", nullable = false)
    @JsonIgnore
    private Taller taller;

    @OneToMany(mappedBy = "equipo")
    @JsonIgnore
    @Builder.Default
    private List<Reparacion> reparaciones = new ArrayList<>();
}
