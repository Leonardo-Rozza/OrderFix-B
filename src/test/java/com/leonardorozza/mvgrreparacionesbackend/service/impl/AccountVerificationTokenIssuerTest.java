package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.AuthTokenRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit evidence of token format and current-account policy; PostgreSQL tests prove the transaction boundary. */
class AccountVerificationTokenIssuerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 34, 56);
    private static final long USER_ID = 7, WORKSHOP_ID = 19;

    @ParameterizedTest @ValueSource(ints = {48, 3, 0, -1})
    void preservesConfiguredHoursAndStoresOnlyTheHashOfThirtyTwoFreshRandomBytes(int hours) throws Exception {
        var fixture = new Fixture(); var random = new FixedRandom();
        var issuer = new AccountVerificationTokenIssuer(fixture.users, fixture.tokens, hours, random, () -> NOW);
        var delivery = issuer.issue(USER_ID, WORKSHOP_ID).orElseThrow();
        byte[] expected = new byte[32]; for (int index = 0; index < expected.length; index++) expected[index] = (byte) index;
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);
        assertThat(delivery.rawToken()).isEqualTo(raw).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(delivery.rawToken())).containsExactly(expected);
        assertThat(random.calls).hasValue(1); assertThat(random.observed).containsOnly((byte) 0);
        var saved = ArgumentCaptor.forClass(AuthToken.class);
        var order = inOrder(fixture.users, fixture.tokens);
        order.verify(fixture.users).findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID);
        order.verify(fixture.tokens).deleteByUserIdAndTipo(USER_ID, TipoAuthToken.VERIFICACION_EMAIL);
        order.verify(fixture.tokens).save(saved.capture()); order.verifyNoMoreInteractions();
        AuthToken token = saved.getValue();
        assertThat(token.getUser()).isSameAs(fixture.current); assertThat(token.getTipo()).isEqualTo(TipoAuthToken.VERIFICACION_EMAIL);
        assertThat(token.getTokenHash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)))).matches("[0-9a-f]{64}").isNotEqualTo(raw);
        assertThat(token.getExpiraEn()).isEqualTo(NOW.plusHours(hours)); assertThat(token.getUsadoEn()).isNull();
        assertThat(token.getId()).isNull(); assertThat(token.getCreatedAt()).isNull();
        assertThat(delivery.recipient()).isEqualTo("current@synthetic.invalid");
        assertThat(delivery.displayName()).isEqualTo("Nombre actual"); assertThat(delivery.validityHours()).isEqualTo(hours);
        assertThat(delivery.toString()).isEqualTo("Delivery[redacted]");
    }

    @Test void springConstructorKeepsTheFortyEightHourDefaultAndLocalDateTimeClock() {
        var fixture = new Fixture();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(UserRepository.class, () -> fixture.users);
            context.registerBean(AuthTokenRepository.class, () -> fixture.tokens);
            context.register(AccountVerificationTokenIssuer.class); context.refresh();
            LocalDateTime before = LocalDateTime.now();
            var delivery = context.getBean(AccountVerificationTokenIssuer.class).issue(USER_ID, WORKSHOP_ID).orElseThrow();
            LocalDateTime after = LocalDateTime.now();
            var saved = ArgumentCaptor.forClass(AuthToken.class); verify(fixture.tokens).save(saved.capture());
            assertThat(delivery.validityHours()).isEqualTo(48);
            assertThat(saved.getValue().getExpiraEn()).isBetween(before.plusHours(48), after.plusHours(48));
        }
    }

    @Test void rereadsIdsAndUsesTheCurrentRecipientAndNameWithoutInspectingSessionCredentials() {
        var fixture = new Fixture();
        fixture.current.setEmail("Changed.Case@Example.COM"); fixture.current.setUsername("Nombre cambiado");
        fixture.current.setRole(UserRole.USER); fixture.current.setTokenVersion(12);
        // No password exists in this fixture: issuing verification is not a second login policy.
        assertThat(fixture.current.getPassword()).isNull();
        var delivery = fixture.issuer().issue(USER_ID, WORKSHOP_ID).orElseThrow();
        assertThat(delivery.recipient()).isEqualTo("Changed.Case@Example.COM");
        assertThat(delivery.displayName()).isEqualTo("Nombre cambiado");
        verify(fixture.users).findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID); verifyNoMoreInteractions(fixture.users);
    }

    @Test void rejectsInvalidIdsWithoutLookupRandomnessOrTokenInvalidation() {
        var fixture = new Fixture(); var random = new FixedRandom();
        var issuer = new AccountVerificationTokenIssuer(fixture.users, fixture.tokens, 48, random, () -> NOW);
        for (Long[] ids : new Long[][]{{null, WORKSHOP_ID}, {USER_ID, null}, {0L, WORKSHOP_ID}, {USER_ID, -1L}}) {
            assertThat(issuer.issue(ids[0], ids[1])).isEmpty();
        }
        verifyNoInteractions(fixture.users, fixture.tokens); assertThat(random.calls).hasValue(0);
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "foreign-user", "foreign-workshop", "no-workshop",
            "inactive-user", "inactive-workshop", "verified", "null-email", "blank-email"})
    void inapplicableCurrentAccountsNeverInvalidateOrIssueAToken(String condition) {
        var fixture = new Fixture();
        switch (condition) {
            case "absent" -> when(fixture.users.findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID)).thenReturn(Optional.empty());
            case "foreign-user" -> fixture.current.setId(USER_ID + 1);
            case "foreign-workshop" -> fixture.current.getTaller().setId(WORKSHOP_ID + 1);
            case "no-workshop" -> fixture.current.setTaller(null);
            case "inactive-user" -> fixture.current.setActive(false);
            case "inactive-workshop" -> fixture.current.getTaller().setActivo(false);
            case "verified" -> fixture.current.setEmailVerificado(true);
            case "null-email" -> fixture.current.setEmail(null);
            case "blank-email" -> fixture.current.setEmail(" ");
            default -> throw new AssertionError(condition);
        }
        var random = new FixedRandom();
        var issuer = new AccountVerificationTokenIssuer(fixture.users, fixture.tokens, 48, random, () -> {
            throw new AssertionError("Skipped accounts must not prepare delivery");
        });
        assertThat(issuer.issue(USER_ID, WORKSHOP_ID)).isEmpty();
        verifyNoInteractions(fixture.tokens); assertThat(random.calls).hasValue(0);
    }

    @Test void preparesEntropyAndExpirationBeforeInvalidatingAnyExistingToken() {
        for (String phase : new String[]{"random", "clock", "overflow"}) {
            var fixture = new Fixture();
            var failure = new IllegalStateException("synthetic private failure");
            var random = new FixedRandom(); random.failure = phase.equals("random") ? failure : null;
            Supplier<LocalDateTime> now = phase.equals("clock") ? () -> { throw failure; }
                    : () -> phase.equals("overflow") ? LocalDateTime.MAX : NOW;
            var issuer = new AccountVerificationTokenIssuer(fixture.users, fixture.tokens, 48, random, now);
            Throwable thrown = catchThrowable(() -> issuer.issue(USER_ID, WORKSHOP_ID));
            if (phase.equals("overflow")) assertThat(thrown).isInstanceOf(java.time.DateTimeException.class);
            else assertThat(thrown).isSameAs(failure);
            verifyNoInteractions(fixture.tokens);
            if (phase.equals("random")) assertThat(random.observed).containsOnly((byte) 0);
        }
    }

    @Test void repositoryFailureNeverReturnsADeliveryOrAttemptsAnAutomaticRetry() {
        for (String phase : new String[]{"read", "delete", "save"}) {
            var fixture = new Fixture(); var failure = new IllegalStateException("private persistence failure");
            switch (phase) {
                case "read" -> when(fixture.users.findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID)).thenThrow(failure);
                case "delete" -> doThrow(failure).when(fixture.tokens).deleteByUserIdAndTipo(USER_ID, TipoAuthToken.VERIFICACION_EMAIL);
                case "save" -> when(fixture.tokens.save(any(AuthToken.class))).thenThrow(failure);
                default -> throw new AssertionError(phase);
            }
            assertThatThrownBy(() -> fixture.issuer().issue(USER_ID, WORKSHOP_ID)).isSameAs(failure);
            verify(fixture.users, times(1)).findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID);
            if (phase.equals("read")) verifyNoInteractions(fixture.tokens);
            else {
                verify(fixture.tokens, times(1)).deleteByUserIdAndTipo(USER_ID, TipoAuthToken.VERIFICACION_EMAIL);
                verify(fixture.tokens, times(phase.equals("save") ? 1 : 0)).save(any(AuthToken.class));
            }
        }
    }

    private static final class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final AuthTokenRepository tokens = mock(AuthTokenRepository.class);
        final User current = User.builder().id(USER_ID).username("Nombre actual").email("current@synthetic.invalid")
                .active(true).emailVerificado(false).role(UserRole.ADMIN)
                .taller(Taller.builder().id(WORKSHOP_ID).activo(true).build()).build();
        Fixture() { when(users.findSessionByIdAndTallerId(USER_ID, WORKSHOP_ID)).thenReturn(Optional.of(current)); }
        AccountVerificationTokenIssuer issuer() { return new AccountVerificationTokenIssuer(users, tokens, 48, new FixedRandom(), () -> NOW); }
    }
    private static final class FixedRandom extends SecureRandom {
        final AtomicInteger calls = new AtomicInteger();
        byte[] observed;
        RuntimeException failure;
        @Override public void nextBytes(byte[] bytes) {
            calls.incrementAndGet(); observed = bytes;
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
            if (failure != null) throw failure;
        }
    }
}
