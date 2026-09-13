package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureAccess;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil.PerfilResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil.PerfilTallerDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.perfil.PerfilUsuarioDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PerfilService {

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PerfilResponseDTO obtener(AuthenticatedUserPrincipal principal) {
        if (principal == null || principal.getUserId() == null || principal.getTallerId() == null) {
            throw new UnauthorizedException("No hay un usuario autenticado asociado a la petición.");
        }

        Long tallerId = tenantService.currentTallerIdForAccount();
        if (!Objects.equals(principal.getTallerId(), tallerId)) {
            throw new UnauthorizedException("La identidad autenticada no corresponde al taller actual.");
        }

        User user = userRepository.findPerfilByIdAndTallerId(principal.getUserId(), tallerId)
                .orElseThrow(() -> new UnauthorizedException("El usuario autenticado ya no está disponible."));
        if (user.getTokenVersion() != principal.getTokenVersion()
                || WorkshopClosureAccess.mode(user, clock.instant()) == WorkshopClosureAccess.Mode.DENIED) {
            throw new UnauthorizedException("El usuario autenticado ya no está disponible.");
        }
        Taller taller = user.getTaller();

        return new PerfilResponseDTO(
                new PerfilUsuarioDTO(user.getId(), user.getUsername(), user.getEmail(), user.getRole()),
                new PerfilTallerDTO(taller.getId(), taller.getNombre(), taller.getTelefono())
        );
    }
}
