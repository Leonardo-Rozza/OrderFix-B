package com.leonardorozza.mvgrreparacionesbackend.service;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.repuesto.RepuestoResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RepuestoService {

    RepuestoResponseDTO crear(RepuestoRequestDTO request);

    RepuestoResponseDTO actualizar(Long id, RepuestoRequestDTO request);

    RepuestoResponseDTO obtenerPorId(Long id);

    Page<RepuestoResponseDTO> listar(String q, Pageable pageable);

    List<RepuestoResponseDTO> listarPorReparacion(Long reparacionId);

    void eliminar(Long id);

}
