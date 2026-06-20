package com.leonardorozza.mvgrreparacionesbackend.service;


import com.leonardorozza.mvgrreparacionesbackend.service.dto.cliente.ClienteRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cliente.ClienteResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ClienteService {

    ClienteResponseDTO crear(ClienteRequestDTO request);

    ClienteResponseDTO actualizar(Long id, ClienteRequestDTO request);

    ClienteResponseDTO obtenerPorId(Long id);

    Page<ClienteResponseDTO> listar(String q, Pageable pageable);

    void eliminar(Long id);
}
