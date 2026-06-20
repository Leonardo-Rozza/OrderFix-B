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
    Reparacion toEntity(ReparacionRequestDTO dto);

    @Mapping(target = "equipoId", source = "equipo.id")
    @Mapping(target = "totalRepuestos", expression = "java(sumaRepuestos(entity))")
    @Mapping(target = "total", expression = "java(total(entity))")
    ReparacionResponseDTO toDTO(Reparacion entity);

    default BigDecimal sumaRepuestos(Reparacion r) {
        if (r.getRepuestos() == null) {
            return BigDecimal.ZERO;
        }
        return r.getRepuestos().stream()
                .map(rep -> rep.getPrecio() == null ? BigDecimal.ZERO : rep.getPrecio())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    default BigDecimal total(Reparacion r) {
        BigDecimal manoDeObra = r.getPrecioFinal() != null ? r.getPrecioFinal()
                : (r.getPrecioEstimado() != null ? r.getPrecioEstimado() : BigDecimal.ZERO);
        return manoDeObra.add(sumaRepuestos(r));
    }
}