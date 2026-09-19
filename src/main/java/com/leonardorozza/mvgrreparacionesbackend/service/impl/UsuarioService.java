package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.PlanLimitException;
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
import java.util.Map;

/**
 * Gestión de empleados (usuarios) del taller. Solo accesible por el ADMIN
 * titular. Los empleados siempre son USER y su rol no se puede promover.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UsuarioService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;
    private final UserSecurityStateLock securityState;
    private final CuentaService cuentaService;

    public UsuarioResponseDTO crear(CrearUsuarioRequestDTO request) {
        validarRolDeEmpleado(request.getRole());
        Long tallerId = tenantService.currentTallerId();
        // FREE permite 1 usuario (el dueño). Para sumar empleados hay que ser PRO.
        if (!planFeatureService.esPro(tallerId) && userRepository.countByTallerId(tallerId) >= 1) {
            throw new PlanLimitException(
                    "Tu plan FREE permite 1 usuario. Pasá a PRO para agregar empleados.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Ya existe una cuenta con ese email.");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .active(true)
                .emailVerificado(false)
                .taller(tenantService.currentTallerRef())
                .build();

        User saved = userRepository.save(user);
        // Reuse the registration flow: issue and deliver only after this employee commits.
        cuentaService.enviarVerificacion(saved);
        return toDTO(saved);
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

        // A request may have loaded this entity before a concurrent personal exit committed.
        securityState.refreshAndLock(user);

        // El rol refleja titular vs empleado y no es una preferencia editable.
        if (request.getRole() != null && request.getRole() != user.getRole()) {
            throw new BadRequestException(
                    "ROL_USUARIO_INMUTABLE",
                    "El rol de un usuario no se puede cambiar desde la gestión de empleados.",
                    Map.of(
                            "rolActual", user.getRole().name(),
                            "rolSolicitado", request.getRole().name()));
        }

        // Evita que el titular se bloquee a sí mismo.
        boolean esMiPropiaCuenta = user.getId().equals(usuarioIdActual());
        if (esMiPropiaCuenta && Boolean.FALSE.equals(request.getActive())) {
            throw new BadRequestException("No podés desactivar tu propia cuenta.");
        }

        if (request.getActive() != null) {
            user.setActive(request.getActive());
        }

        return toDTO(userRepository.save(user));
    }

    private void validarRolDeEmpleado(UserRole roleSolicitado) {
        if (roleSolicitado == null || roleSolicitado == UserRole.USER) {
            return;
        }
        throw new BadRequestException(
                "EMPLEADO_DEBE_SER_USER",
                "Los empleados se crean con rol USER; el ADMIN titular es único por taller.",
                Map.of(
                        "rolPermitido", UserRole.USER.name(),
                        "rolSolicitado", roleSolicitado.name()));
    }

    private Long usuarioIdActual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private UsuarioResponseDTO toDTO(User u) {
        return new UsuarioResponseDTO(
                u.getId(), u.getUsername(), u.getEmail(), u.getRole(), Boolean.TRUE.equals(u.getActive()));
    }
}
