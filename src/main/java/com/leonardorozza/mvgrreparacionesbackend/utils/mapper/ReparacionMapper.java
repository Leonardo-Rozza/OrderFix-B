package com.leonardorozza.mvgrreparacionesbackend.utils.mapper;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Reparacion;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface ReparacionMapper {

    @Mapping(target = "equipo.id", source = "equipoId")
    @Mapping(target = "tecnico", ignore = true) // se resuelve en el service (valida que sea del taller)
    @Mapping(target = "fotos", ignore = true)   // se setea en el service (evita lista null por @Builder)
    Reparacion toEntity(ReparacionRequestDTO dto);

    @Mapping(target = "equipoId", source = "equipo.id")
    @Mapping(target = "tecnicoId", source = "tecnico.id")
    @Mapping(target = "tecnicoNombre", source = "tecnico.username")
    @Mapping(target = "totalRepuestos", expression = "java(entity.calcularTotalRepuestos())")
    @Mapping(target = "total", expression = "java(entity.calcularTotal())")
    ReparacionResponseDTO toDTO(Reparacion entity);
}