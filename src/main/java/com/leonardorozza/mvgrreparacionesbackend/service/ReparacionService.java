package com.leonardorozza.mvgrreparacionesbackend.service;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoReparacion;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.IngresoRapidoResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.WhatsappLinkDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ReparacionService {

        ReparacionResponseDTO crear(ReparacionRequestDTO request);

        /** Carga rápida: crea (o reutiliza) cliente + equipo + reparación de una sola vez. */
        IngresoRapidoResponseDTO crearIngresoRapido(IngresoRapidoRequestDTO request);

        ReparacionResponseDTO actualizar(Long id, ReparacionRequestDTO request);

        ReparacionResponseDTO cambiarEstado(Long id, EstadoReparacion nuevoEstado);

        ReparacionResponseDTO obtenerPorId(Long id);

        Page<ReparacionResponseDTO> listar(String q, EstadoReparacion estado, Pageable pageable);

        List<ReparacionResponseDTO> listarPorEquipo(Long equipoId);

        List<ReparacionResponseDTO> listarPorEstado(EstadoReparacion estado);

        void eliminar(Long id);

        /** Arma un link de WhatsApp (wa.me) para avisar al cliente, con el link de seguimiento. */
        WhatsappLinkDTO linkWhatsapp(Long id);

}
