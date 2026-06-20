package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Cliente;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.ClienteRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.ClienteService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cliente.ClienteRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cliente.ClienteResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.utils.mapper.ClienteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ClienteServiceImpl implements ClienteService {

    private final ClienteRepository clienteRepository;
    private final ClienteMapper clienteMapper;
    private final TenantService tenantService;

    @Override
    public ClienteResponseDTO crear(ClienteRequestDTO request) {
        Long tallerId = tenantService.currentTallerId();

        if (clienteRepository.existsByTelefonoAndTallerId(request.getTelefono(), tallerId)) {
            throw new BadRequestException("Ya existe un cliente con ese teléfono.");
        }

        Cliente cliente = clienteMapper.toEntity(request);
        cliente.setTaller(tenantService.currentTallerRef());
        clienteRepository.save(cliente);
        return clienteMapper.toDTO(cliente);
    }

    @Override
    public ClienteResponseDTO actualizar(Long id, ClienteRequestDTO request) {
        Cliente cliente = clienteRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));

        cliente.setNombre(request.getNombre());
        cliente.setApellido(request.getApellido());
        cliente.setTelefono(request.getTelefono());
        cliente.setEmail(request.getEmail());
        cliente.setDireccion(request.getDireccion());

        return clienteMapper.toDTO(cliente);
    }

    @Override
    @Transactional(readOnly = true)
    public ClienteResponseDTO obtenerPorId(Long id) {
        Cliente cliente = clienteRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
        return clienteMapper.toDTO(cliente);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ClienteResponseDTO> listar(String q, Pageable pageable) {
        return clienteRepository.search(tenantService.currentTallerId(), q, pageable)
                .map(clienteMapper::toDTO);
    }

    @Override
    public void eliminar(Long id) {
        Cliente cliente = clienteRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));

        clienteRepository.delete(cliente);
    }
}
