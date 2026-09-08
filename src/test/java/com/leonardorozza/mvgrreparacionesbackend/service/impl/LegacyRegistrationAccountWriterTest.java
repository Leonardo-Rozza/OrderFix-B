package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Suscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoSuscripcion;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PlanType;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.SuscripcionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.TallerRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Mocks establish values, ordering and declaration; PostgreSQL accredits the actual transaction. */
@ExtendWith(MockitoExtension.class)
class LegacyRegistrationAccountWriterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T01:15:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
    private static final String PASSWORD = "  Exact-secret-é-123  ";
    private static final String ENCODED = "synthetic-encoded-password";
    private static final long WORKSHOP_ID = 52L;
    private static final long USER_ID = 83L;

    @Mock private TallerRepository workshops;
    @Mock private SuscripcionRepository subscriptions;
    @Mock private UserRepository users;
    @Mock private PasswordEncoder encoder;
    private LegacyRegistrationAccountWriter writer;

    @BeforeEach void configureWriter() { writer = writer(14); }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "  ", " +54 11 "})
    void preservesTheExactBusinessValuesAndHistoricalDefaultsAcrossTheThreeWrites(String phone) {
        RegisterRequestDto request = request(phone); prepareSuccessfulWrites();

        var result = writer.create(request);

        var workshop = ArgumentCaptor.forClass(Taller.class);
        var subscription = ArgumentCaptor.forClass(Suscripcion.class);
        var admin = ArgumentCaptor.forClass(User.class);
        var ordered = inOrder(users, workshops, subscriptions, encoder);
        ordered.verify(users).existsByEmail(request.email());
        ordered.verify(workshops).save(workshop.capture());
        ordered.verify(subscriptions).save(subscription.capture());
        ordered.verify(encoder).encode(PASSWORD);
        ordered.verify(users).save(admin.capture());
        verifyNoMoreInteractions(users, workshops, subscriptions, encoder);

        Taller savedWorkshop = workshop.getValue();
        assertThat(savedWorkshop.getNombre()).isEqualTo(" Taller e\u0301 ");
        assertThat(savedWorkshop.getEmailContacto()).isEqualTo("CaseSensitive@Example.COM");
        assertThat(savedWorkshop.getTelefono()).isEqualTo(phone);
        assertThat(savedWorkshop.getActivo()).isTrue(); assertThat(savedWorkshop.isMostrarEnResumen()).isTrue();
        assertThat(savedWorkshop.getSecuenciaOrden()).isZero(); assertThat(savedWorkshop.getAnioSecuenciaOrden()).isNull();
        assertThat(savedWorkshop.getAliasCobro()).isNull(); assertThat(savedWorkshop.getTitularCobro()).isNull();
        assertThat(savedWorkshop.getEntidadCobro()).isNull(); assertThat(savedWorkshop.getSuscripcion()).isNull();
        assertThat(savedWorkshop.getCreatedAt()).isNull(); assertThat(savedWorkshop.getUpdatedAt()).isNull();

        Suscripcion savedSubscription = subscription.getValue();
        assertThat(savedSubscription.getTaller()).isSameAs(savedWorkshop);
        assertThat(savedSubscription.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(savedSubscription.getEstado()).isEqualTo(EstadoSuscripcion.TRIAL);
        assertThat(savedSubscription.getFechaInicio()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(savedSubscription.getFechaFinTrial()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(savedSubscription.getReparacionesMes()).isZero(); assertThat(savedSubscription.getConsumoMes()).isNull();
        assertThat(savedSubscription.getProximoCobro()).isNull(); assertThat(savedSubscription.getMpPreapprovalId()).isNull();
        assertThat(savedSubscription.getMpPayerId()).isNull(); assertThat(savedSubscription.getMpStatus()).isNull();
        assertThat(savedSubscription.getMpExternalReference()).isNull(); assertThat(savedSubscription.getMpCheckoutInitPoint()).isNull();
        assertThat(savedSubscription.getMpNextPaymentAt()).isNull(); assertThat(savedSubscription.getMpLastPaymentAt()).isNull();
        assertThat(savedSubscription.getMpLastAuthorizedPaymentId()).isNull();
        assertThat(savedSubscription.getCreatedAt()).isNull(); assertThat(savedSubscription.getUpdatedAt()).isNull();

        User savedAdmin = admin.getValue();
        assertThat(savedAdmin.getTaller()).isSameAs(savedWorkshop);
        assertThat(savedAdmin.getUsername()).isEqualTo(" Admin 😀 "); assertThat(savedAdmin.getEmail()).isEqualTo(request.email());
        assertThat(savedAdmin.getPassword()).isEqualTo(ENCODED).isNotEqualTo(PASSWORD);
        assertThat(savedAdmin.getRole()).isEqualTo(UserRole.ADMIN); assertThat(savedAdmin.getActive()).isTrue();
        assertThat(savedAdmin.getEmailVerificado()).isFalse(); assertThat(savedAdmin.getTokenVersion()).isZero();
        assertThat(result).isEqualTo(new LegacyRegistrationAccountWriter.Identity(USER_ID, WORKSHOP_ID));
        assertThat(result.toString()).isEqualTo("Identity[redacted]");
    }

    @ParameterizedTest @ValueSource(ints = {-7, 0, 1, 30})
    void configuredTrialDaysRetainTheHistoricalPlusDaysSemantics(int days) {
        writer = writer(days); prepareSuccessfulWrites();

        writer.create(request(null));

        var subscription = ArgumentCaptor.forClass(Suscripcion.class); verify(subscriptions).save(subscription.capture());
        assertThat(subscription.getValue().getFechaInicio()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(subscription.getValue().getFechaFinTrial()).isEqualTo(LocalDate.of(2026, 9, 8).plusDays(days));
    }

    @Test void anExistingExactEmailRejectsBeforeAnyWriteClockOrEncoding() {
        RegisterRequestDto request = request(null);
        when(users.existsByEmail(request.email())).thenReturn(true);
        Clock unusedClock = org.mockito.Mockito.mock(Clock.class);
        writer = new LegacyRegistrationAccountWriter(workshops, subscriptions, users, encoder, unusedClock, 14);

        assertThatThrownBy(() -> writer.create(request)).isExactlyInstanceOf(BadRequestException.class)
                .hasMessage("Ya existe una cuenta con ese email.").hasNoCause()
                .satisfies(failure -> assertThat(((BadRequestException) failure).getCode()).isNull());

        verify(users).existsByEmail(request.email()); verifyNoMoreInteractions(users);
        verifyNoInteractions(workshops, subscriptions, encoder, unusedClock);
    }

    @ParameterizedTest @EnumSource(FailurePhase.class)
    void failuresStopAtTheirPhaseAndPropagateWithoutRetryOrCompensation(FailurePhase phase) {
        RegisterRequestDto request = request(null);
        var failure = new DataAccessResourceFailureException("Synthetic creation failure");
        if (phase == FailurePhase.EMAIL_CHECK) when(users.existsByEmail(request.email())).thenThrow(failure);
        else if (phase == FailurePhase.WORKSHOP) when(workshops.save(any(Taller.class))).thenThrow(failure);
        else {
            when(workshops.save(any(Taller.class))).thenAnswer(invocation -> {
                Taller value = invocation.getArgument(0); value.setId(WORKSHOP_ID); return value;
            });
            if (phase == FailurePhase.SUBSCRIPTION) when(subscriptions.save(any(Suscripcion.class))).thenThrow(failure);
            else if (phase == FailurePhase.PASSWORD) when(encoder.encode(PASSWORD)).thenThrow(failure);
            else {
                when(encoder.encode(PASSWORD)).thenReturn(ENCODED);
                when(users.save(any(User.class))).thenThrow(failure);
            }
        }

        assertThatThrownBy(() -> writer.create(request)).isSameAs(failure);

        var ordered = inOrder(users, workshops, subscriptions, encoder);
        ordered.verify(users).existsByEmail(request.email());
        if (phase.ordinal() >= FailurePhase.WORKSHOP.ordinal()) ordered.verify(workshops).save(any(Taller.class));
        if (phase.ordinal() >= FailurePhase.SUBSCRIPTION.ordinal()) ordered.verify(subscriptions).save(any(Suscripcion.class));
        if (phase.ordinal() >= FailurePhase.PASSWORD.ordinal()) ordered.verify(encoder).encode(PASSWORD);
        if (phase == FailurePhase.USER) ordered.verify(users).save(any(User.class));
        else verify(users, never()).save(any(User.class));
        verifyNoMoreInteractions(users, workshops, subscriptions, encoder);
    }

    @ParameterizedTest @MethodSource("invalidIdentities")
    void theIdentityCannotRepresentMissingOrNonpositiveGeneratedIds(long userId, long tallerId) {
        assertThatThrownBy(() -> new LegacyRegistrationAccountWriter.Identity(userId, tallerId))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("La identidad de cuenta no es válida.")
                .hasNoCause();
    }
    static Stream<Arguments> invalidIdentities() {
        return Stream.of(Arguments.of(0L, WORKSHOP_ID), Arguments.of(-1L, WORKSHOP_ID),
                Arguments.of(USER_ID, 0L), Arguments.of(USER_ID, -1L));
    }

    @Test void theDeclaredWriterBoundaryIsIndependentMutableAndReadCommittedWithTheExistingTrialDefault() throws Exception {
        var boundary = LegacyRegistrationAccountWriter.class.getMethod("create", RegisterRequestDto.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull(); assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(boundary.isolation()).isEqualTo(Isolation.READ_COMMITTED); assertThat(boundary.readOnly()).isFalse();
        assertThat(boundary.timeout()).isEqualTo(-1);
        var parameter = LegacyRegistrationAccountWriter.class.getConstructors()[0].getParameters()[5];
        assertThat(parameter.getAnnotation(Value.class).value()).isEqualTo("${plan.trial-dias:14}");
    }

    private void prepareSuccessfulWrites() {
        when(workshops.save(any(Taller.class))).thenAnswer(invocation -> {
            Taller value = invocation.getArgument(0); value.setId(WORKSHOP_ID); return value;
        });
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User value = invocation.getArgument(0); value.setId(USER_ID); return value;
        });
        when(encoder.encode(PASSWORD)).thenReturn(ENCODED);
    }
    private LegacyRegistrationAccountWriter writer(int days) {
        return new LegacyRegistrationAccountWriter(workshops, subscriptions, users, encoder, CLOCK, days);
    }
    private static RegisterRequestDto request(String phone) {
        return new RegisterRequestDto(" Taller e\u0301 ", phone, " Admin 😀 ", "CaseSensitive@Example.COM", PASSWORD);
    }
    private enum FailurePhase { EMAIL_CHECK, WORKSHOP, SUBSCRIPTION, PASSWORD, USER }
}
