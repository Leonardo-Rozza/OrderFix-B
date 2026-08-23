package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.taller.DatosCobroRequestDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatosCobroTallerServiceTests {

    @Mock
    private TallerRepository tallerRepository;

    @Mock
    private TenantService tenantService;

    @Mock
    private PlanFeatureService planFeatureService;

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

        var response = service.actualizar(
                principal,
                new DatosCobroRequestDTO(" taller.mp ", " Titular ", " Banco ", false));

        verify(tallerRepository).findByIdForUpdate(7L);
        verify(tallerRepository, never()).findById(7L);
        assertThat(taller.getSecuenciaOrden()).isEqualTo(42);
        assertThat(response.alias()).isEqualTo("taller.mp");
        assertThat(response.mostrarEnResumen()).isFalse();
    }
}
