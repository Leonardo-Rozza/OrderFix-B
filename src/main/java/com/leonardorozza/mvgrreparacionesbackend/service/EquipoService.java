package com.leonardorozza.mvgrreparacionesbackend.service;


import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface EquipoService {
    EquipoResponseDTO crear(EquipoRequestDTO request);

    EquipoResponseDTO actualizar(Long id, EquipoRequestDTO request);

    EquipoResponseDTO obtenerPorId(Long id);

    Page<EquipoResponseDTO> listar(String q, Pageable pageable);

    List<EquipoResponseDTO> listarPorCliente(Long clienteId);

    void eliminar(Long id);
}
