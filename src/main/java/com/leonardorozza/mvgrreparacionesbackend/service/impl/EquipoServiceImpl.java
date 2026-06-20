package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Equipo;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.EquipoRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.EquipoService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.equipo.EquipoResponseDTO;

import com.leonardorozza.mvgrreparacionesbackend.utils.mapper.EquipoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class EquipoServiceImpl implements EquipoService {

    private final EquipoRepository equipoRepository;
    private final ClienteRepository clienteRepository;
    private final EquipoMapper equipoMapper;
    private final TenantService tenantService;

    @Override
    public EquipoResponseDTO crear(EquipoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Cliente cliente = clienteRepository.findByIdAndTallerId(request.getClienteId(), tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado con ID: " + request.getClienteId()));

        Equipo equipo = equipoMapper.toEntity(request);
        equipo.setCliente(cliente);
        equipo.setTaller(tenantService.currentTallerRef());

        Equipo guardado = equipoRepository.save(equipo);
        return equipoMapper.toDTO(guardado);
    }

    @Override
    public EquipoResponseDTO actualizar(Long id, EquipoRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        Equipo equipo = equipoRepository.findByIdAndTallerId(id, tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado con ID: " + id));

        Cliente cliente = clienteRepository.findByIdAndTallerId(request.getClienteId(), tallerId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado con ID: " + request.getClienteId()));

        equipo.setMarca(request.getMarca());
        equipo.setModelo(request.getModelo());
        equipo.setImei(request.getImei());
        equipo.setColor(request.getColor());
        equipo.setDescripcion(request.getDescripcion());
        equipo.setCliente(cliente);

        return equipoMapper.toDTO(equipoRepository.save(equipo));
    }

    @Override
    @Transactional(readOnly = true)
    public EquipoResponseDTO obtenerPorId(Long id) {
        Equipo equipo = equipoRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado con ID: " + id));

        return equipoMapper.toDTO(equipo);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EquipoResponseDTO> listar(String q, Pageable pageable) {
        return equipoRepository.search(tenantService.currentTallerId(), q, pageable)
                .map(equipoMapper::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EquipoResponseDTO> listarPorCliente(Long clienteId) {
        Long tallerId = tenantService.currentTallerId();

        if (!clienteRepository.existsByIdAndTallerId(clienteId, tallerId)) {
            throw new ResourceNotFoundException("Cliente no encontrado con ID: " + clienteId);
        }

        return equipoRepository.findByClienteIdAndTallerId(clienteId, tallerId)
                .stream()
                .map(equipoMapper::toDTO)
                .toList();
    }

    @Override
    public void eliminar(Long id) {
        Equipo equipo = equipoRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Equipo no encontrado con ID: " + id));

        equipoRepository.delete(equipo);
    }
}
