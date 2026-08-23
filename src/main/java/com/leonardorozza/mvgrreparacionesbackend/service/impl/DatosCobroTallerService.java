package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ResourceNotFoundException;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.TallerQrCobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanFeature;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerQrCobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.imagen.QrCobroNormalizador;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DatosCobroTallerService {

    private final TallerRepository tallerRepository;
    private final TallerQrCobroRepository tallerQrCobroRepository;
    private final TenantService tenantService;
    private final PlanFeatureService planFeatureService;
    private final QrCobroNormalizador qrCobroNormalizador;

    @Transactional(readOnly = true)
    public DatosCobroDTO obtener(AuthenticatedUserPrincipal principal) {
        Long tallerId = tallerIdActual(principal);
        planFeatureService.requerir(PlanFeature.COBROS);
        return toDTO(tallerActual(tallerId, false));
    }

    @Transactional
    public DatosCobroDTO actualizar(
            AuthenticatedUserPrincipal principal, DatosCobroRequestDTO request) {
        Long tallerId = tallerIdActual(principal);
        planFeatureService.requerir(PlanFeature.COBROS);
        Taller taller = tallerActual(tallerId, true);

        // PUT reemplaza el recurso completo: un null o blanco borra el valor anterior.
        taller.setAliasCobro(request.alias());
        taller.setTitularCobro(request.titular());
        taller.setEntidadCobro(request.entidad());
        taller.setMostrarEnResumen(request.mostrarEnResumen());

        return toDTO(tallerRepository.save(taller));
    }

    @Transactional(readOnly = true)
    public QrCobroContenido obtenerQr(AuthenticatedUserPrincipal principal) {
        Long tallerId = tallerIdActual(principal);
        planFeatureService.requerir(PlanFeature.COBROS);
        TallerQrCobro qr = tallerQrCobroRepository.findById(tallerId)
                .orElseThrow(this::qrNoConfigurado);
        return new QrCobroContenido(qr.getPng(), qr.getSha256());
    }

    @Transactional
    public DatosCobroDTO actualizarQr(
            AuthenticatedUserPrincipal principal, MultipartFile archivo) {
        Long tallerId = tallerIdActual(principal);
        planFeatureService.requerir(PlanFeature.COBROS);

        // La lectura, inspección, decodificación y re-encode terminan antes del lock
        // y antes de mutar la fila vigente: una imagen inválida nunca pisa el QR anterior.
        var normalizado = qrCobroNormalizador.normalizar(archivo);
        Taller taller = tallerActual(tallerId, true);
        TallerQrCobro qr = tallerQrCobroRepository.findById(tallerId)
                .orElseGet(() -> TallerQrCobro.builder().taller(taller).build());
        qr.setPng(normalizado.png());
        qr.setSha256(normalizado.sha256());
        tallerQrCobroRepository.saveAndFlush(qr);

        return toDTO(taller);
    }

    @Transactional
    public DatosCobroDTO eliminarQr(AuthenticatedUserPrincipal principal) {
        Long tallerId = tallerIdActual(principal);
        planFeatureService.requerir(PlanFeature.COBROS);
        Taller taller = tallerActual(tallerId, true);
        tallerQrCobroRepository.deleteByTallerId(tallerId);
        return toDTO(taller);
    }

    private Long tallerIdActual(AuthenticatedUserPrincipal principal) {
        Long tallerId = tenantService.currentTallerId();
        if (principal == null
                || principal.getUserId() == null
                || !Objects.equals(principal.getTallerId(), tallerId)) {
            throw new UnauthorizedException(
                    "La identidad autenticada no corresponde al taller actual.");
        }
        return tallerId;
    }

    private Taller tallerActual(Long tallerId, boolean forUpdate) {
        var taller = forUpdate
                ? tallerRepository.findByIdForUpdate(tallerId)
                : tallerRepository.findById(tallerId);
        return taller
                .orElseThrow(() -> new ResourceNotFoundException("Taller no encontrado."));
    }

    private DatosCobroDTO toDTO(Taller taller) {
        String qrVersion = tallerQrCobroRepository.findSha256ByTallerId(taller.getId())
                .orElse(null);
        return new DatosCobroDTO(
                taller.getAliasCobro(),
                taller.getTitularCobro(),
                taller.getEntidadCobro(),
                taller.isMostrarEnResumen(),
                qrVersion != null,
                qrVersion);
    }

    private ResourceNotFoundException qrNoConfigurado() {
        return new ResourceNotFoundException(
                "QR_COBRO_NO_CONFIGURADO",
                "El taller no tiene un QR de cobro configurado.");
    }

    public record QrCobroContenido(byte[] png, String sha256) {
        public QrCobroContenido {
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }
}
