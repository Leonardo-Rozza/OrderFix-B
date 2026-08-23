package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.TallerQrCobro;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerQrCobroRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.imagen.QrCobroNormalizador;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatosCobroTallerServiceTests {

    @Mock
    private TallerRepository tallerRepository;

    @Mock
    private TallerQrCobroRepository tallerQrCobroRepository;

    @Mock
    private TenantService tenantService;

    @Mock
    private PlanFeatureService planFeatureService;

    @Mock
    private QrCobroNormalizador qrCobroNormalizador;

    @Mock
    private AuthenticatedUserPrincipal principal;

    @InjectMocks
    private DatosCobroTallerService service;

    @Test
    void putTomaLockDelTallerAntesDeReemplazarLosMetadatos() {
        Taller taller = Taller.builder()
                .id(7L)
                .nombre("Taller con secuencia")
                .secuenciaOrden(42)
                .build();
        when(tenantService.currentTallerId()).thenReturn(7L);
        when(principal.getUserId()).thenReturn(11L);
        when(principal.getTallerId()).thenReturn(7L);
        when(tallerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(taller));
        when(tallerRepository.save(taller)).thenReturn(taller);
        when(tallerQrCobroRepository.findSha256ByTallerId(7L))
                .thenReturn(Optional.of("a".repeat(64)));

        var response = service.actualizar(
                principal,
                new DatosCobroRequestDTO(" taller.mp ", " Titular ", " Banco ", false));

        verify(tallerRepository).findByIdForUpdate(7L);
        verify(tallerRepository, never()).findById(7L);
        verify(tallerQrCobroRepository).findSha256ByTallerId(7L);
        verify(tallerQrCobroRepository, never()).findById(any());
        assertThat(taller.getSecuenciaOrden()).isEqualTo(42);
        assertThat(response.alias()).isEqualTo("taller.mp");
        assertThat(response.mostrarEnResumen()).isFalse();
        assertThat(response.qrDisponible()).isTrue();
        assertThat(response.qrVersion()).isEqualTo("a".repeat(64));
    }

    @Test
    void putQrNormalizaAntesDelLockYRecienLuegoReemplazaLaFila() {
        Taller taller = Taller.builder().id(8L).nombre("Taller QR").build();
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        String sha = "b".repeat(64);
        var archivo = new MockMultipartFile("file", "qr.jpg", "image/jpeg", png);
        var normalizado = new QrCobroNormalizador.QrCobroNormalizado(png, sha);

        when(tenantService.currentTallerId()).thenReturn(8L);
        when(principal.getUserId()).thenReturn(12L);
        when(principal.getTallerId()).thenReturn(8L);
        when(qrCobroNormalizador.normalizar(archivo)).thenReturn(normalizado);
        when(tallerRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(taller));
        when(tallerQrCobroRepository.findById(8L)).thenReturn(Optional.empty());
        when(tallerQrCobroRepository.saveAndFlush(any(TallerQrCobro.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tallerQrCobroRepository.findSha256ByTallerId(8L))
                .thenReturn(Optional.of(sha));

        var response = service.actualizarQr(principal, archivo);

        InOrder orden = inOrder(qrCobroNormalizador, tallerRepository, tallerQrCobroRepository);
        orden.verify(qrCobroNormalizador).normalizar(archivo);
        orden.verify(tallerRepository).findByIdForUpdate(8L);
        orden.verify(tallerQrCobroRepository).findById(8L);
        orden.verify(tallerQrCobroRepository).saveAndFlush(any(TallerQrCobro.class));
        assertThat(response.qrDisponible()).isTrue();
        assertThat(response.qrVersion()).isEqualTo(sha);
    }

    @Test
    void deleteQrLockeaElTallerYNoNecesitaCargarElBytea() {
        Taller taller = Taller.builder().id(9L).nombre("Taller delete QR").build();
        when(tenantService.currentTallerId()).thenReturn(9L);
        when(principal.getUserId()).thenReturn(13L);
        when(principal.getTallerId()).thenReturn(9L);
        when(tallerRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(taller));
        when(tallerQrCobroRepository.findSha256ByTallerId(9L)).thenReturn(Optional.empty());

        var response = service.eliminarQr(principal);

        InOrder orden = inOrder(tallerRepository, tallerQrCobroRepository);
        orden.verify(tallerRepository).findByIdForUpdate(9L);
        orden.verify(tallerQrCobroRepository).deleteByTallerId(9L);
        verify(tallerQrCobroRepository, never()).findById(9L);
        assertThat(response.qrDisponible()).isFalse();
        assertThat(response.qrVersion()).isNull();
    }
}
