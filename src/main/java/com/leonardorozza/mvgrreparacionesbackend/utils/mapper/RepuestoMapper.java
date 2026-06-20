package com.leonardorozza.mvgrreparacionesbackend.utils.mapper;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Repuesto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoResponseDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RepuestoMapper {

    @Mapping(target = "reparacion.id", source = "reparacionId")
    @Mapping(target = "articulo", ignore = true) // se resuelve en el service (valida taller y descuenta stock)
    @Mapping(target = "cantidad", ignore = true) // se setea en el service
    Repuesto toEntity(RepuestoRequestDTO dto);

    @Mapping(target = "reparacionId", source = "reparacion.id")
    @Mapping(target = "articuloId", source = "articulo.id")
    @Mapping(target = "reparacionEquipo", expression = "java(labelEquipo(entity))")
    RepuestoResponseDTO toDTO(Repuesto entity);

    default String labelEquipo(Repuesto r) {
        if (r.getReparacion() == null || r.getReparacion().getEquipo() == null) {
            return null;
        }
        var e = r.getReparacion().getEquipo();
        return e.getMarca() + " " + e.getModelo();
    }
}