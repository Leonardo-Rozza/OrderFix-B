package com.leonardorozza.mvgrreparacionesbackend.controller;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.cuenta.PersonalAccessExitRequest;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountAccessExitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class AccountAccessExitController {
    public static final String PATH = "/api/cuenta/baja-acceso";
    private final AccountAccessExitService service;

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            throw new BadRequestException("BAJA_ACCESO_INVALIDA", "La baja de acceso no admite parámetros de consulta.");
        }
        PersonalAccessExitRequest input = PersonalAccessExitRequest.read(request.getInputStream());
        // The transactional service proxy commits before returning the successful HTTP result.
        service.deactivate(principal, input.passwordActual());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}
