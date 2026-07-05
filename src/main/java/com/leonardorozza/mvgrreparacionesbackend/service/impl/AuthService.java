package com.leonardorozza.mvgrreparacionesbackend.service.impl;


import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    public AuthResponseDto login(String email, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        // Recuperamos el taller (tenant) para incluirlo como claim en el token
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));

        Long tallerId = user.getTaller() != null ? user.getTaller().getId() : null;

        String token = jwtUtils.generateToken(userDetails, tallerId);
        return new AuthResponseDto(token, "Bearer", email, Boolean.TRUE.equals(user.getEmailVerificado()));
    }
}

