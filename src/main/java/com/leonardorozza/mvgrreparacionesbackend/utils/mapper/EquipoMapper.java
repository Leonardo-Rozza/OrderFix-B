package com.leonardorozza.mvgrreparacionesbackend.utils.mapper;


import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoResponseDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EquipoMapper {

    @Mapping(target = "cliente.id", source = "clienteId")
    Equipo toEntity(EquipoRequestDTO dto);

    @Mapping(target = "clienteId", source = "cliente.id")
    @Mapping(target = "clienteNombre", source = "cliente.nombre")
    @Mapping(target = "clienteApellido", source = "cliente.apellido")
    @Mapping(target = "clienteTelefono", source = "cliente.telefono")
    @Mapping(target = "reparacionesCount", ignore = true) // se setea en el service (agregado por página)
    EquipoResponseDTO toDTO(Equipo entity);
}