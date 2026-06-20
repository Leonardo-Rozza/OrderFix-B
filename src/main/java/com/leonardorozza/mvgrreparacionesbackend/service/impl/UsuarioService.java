package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario.ActualizarUsuarioRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario.CrearUsuarioRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.usuario.UsuarioResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestión de empleados (usuarios) del taller. Solo accesible por ADMIN.
 * Incluye guardas para que un ADMIN no se bloquee a sí mismo.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UsuarioService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantService tenantService;

    public UsuarioResponseDTO crear(CrearUsuarioRequestDTO request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Ya existe una cuenta con ese email.");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : UserRole.USER)
                .active(true)
                .taller(tenantService.currentTallerRef())
                .build();

        return toDTO(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<UsuarioResponseDTO> listar() {
        return userRepository.findAllByTallerId(tenantService.currentTallerId())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public UsuarioResponseDTO obtener(Long id) {
        return userRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .map(this::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado con ID: " + id));
    }

    public UsuarioResponseDTO actualizar(Long id, ActualizarUsuarioRequestDTO request) {
        User user = userRepository.findByIdAndTallerId(id, tenantService.currentTallerId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado con ID: " + id));

        // Evita que un ADMIN se bloquee a sí mismo
        boolean esMiPropiaCuenta = user.getEmail().equals(emailActual());
        if (esMiPropiaCuenta) {
            if (Boolean.FALSE.equals(request.getActive())) {
                throw new BadRequestException("No podés desactivar tu propia cuenta.");
            }
            if (request.getRole() != null && request.getRole() != UserRole.ADMIN) {
                throw new BadRequestException("No podés quitarte el rol de ADMIN a vos mismo.");
            }
        }

        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        return toDTO(userRepository.save(user));
    }

    private String emailActual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private UsuarioResponseDTO toDTO(User u) {
        return new UsuarioResponseDTO(
                u.getId(), u.getUsername(), u.getEmail(), u.getRole(), Boolean.TRUE.equals(u.getActive()));
    }
}
