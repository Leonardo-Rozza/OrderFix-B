package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommandValidator;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalActorSnapshot;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegalRegistrationPreparationTest {
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String HASH = "$2a$10$" + "A".repeat(53);
    private static final String MESSAGE = "La preparación de registro no es válida.";
    private static final Instant NOW = Instant.parse("2026-09-08T01:02:03.123456789Z");
    private static final Clock APPLICATION_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDateTime AUDIT = LocalDateTime.ofInstant(NOW, ZoneId.of("America/Argentina/Buenos_Aires"));

    @Test
    void requiresAllThreeCollaboratorsWithoutLeakingTheirDiagnostics() {
        BCryptPasswordEncoder encoder = stubEncoder();
        assertInvalid(() -> new LegalRegistrationPreparation(null, APPLICATION_CLOCK, () -> AUDIT, 14));
        assertInvalid(() -> new LegalRegistrationPreparation(encoder, null, () -> AUDIT, 14));
        assertInvalid(() -> new LegalRegistrationPreparation(encoder, APPLICATION_CLOCK, null, 14));
        verifyNoInteractions(encoder);
    }

    @Test
    void rejectsMissingOrAuthenticatedCommandsAndMissingBudgetBeforeEncoding() {
        BCryptPasswordEncoder encoder = stubEncoder();
        var preparation = preparation(encoder);
        var authenticated = LegalAcceptanceCommandValidator.authenticated(
                new LegalActorSnapshot(1, 2, UserRole.ADMIN, 0, true, true), REVISION, List.of());
        assertInvalid(() -> preparation.prepare(null, deadline()));
        assertInvalid(() -> preparation.prepare(authenticated, deadline()));
        assertInvalid(() -> preparation.prepare(command("secret-pass"), null));
        verifyNoInteractions(encoder);
    }

    @Test
    void defaultBCryptPreservesTheOriginalPasswordAndUsesAnIndependentSalt() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        var command = command("  contraseña🔧  ");
        var preparation = preparation(encoder);
        var first = preparation.prepare(command, deadline());
        var second = preparation.prepare(command, deadline());
        assertThat(first.encodedPassword()).startsWith("$2a$10$").hasSize(60);
        assertThat(second.encodedPassword()).startsWith("$2a$10$").hasSize(60)
                .isNotEqualTo(first.encodedPassword());
        assertThat(encoder.matches(command.registration().password(), first.encodedPassword())).isTrue();
        assertThat(encoder.matches(command.registration().password().trim(), first.encodedPassword())).isFalse();
        assertThat(first.registration()).isSameAs(command.registration());
    }

    @ParameterizedTest
    @MethodSource("passwordsAtByteLimit")
    void preservesTheInclusiveSeventyTwoByteBCryptBoundary(String password) {
        var encoder = new BCryptPasswordEncoder();
        var result = preparation(encoder).prepare(command(password), deadline());
        assertThat(result.encodedPassword()).startsWith("$2a$10$").hasSize(60);
        assertThat(encoder.matches(password, result.encodedPassword())).isTrue();
    }

    static Stream<String> passwordsAtByteLimit() {
        return Stream.of("a".repeat(72), "é".repeat(36), "🔧".repeat(18));
    }

    @ParameterizedTest
    @MethodSource("passwordsBeyondByteLimit")
    void rejectsPasswordsBeyondTheByteLimitWithoutTruncatingOrObservingDates(String password) {
        AtomicInteger audits = new AtomicInteger();
        Clock unusedClock = mock(Clock.class);
        var preparation = new LegalRegistrationPreparation(new BCryptPasswordEncoder(), unusedClock,
                () -> { audits.incrementAndGet(); return AUDIT; }, 14);
        assertInvalid(() -> preparation.prepare(command(password), deadline()));
        assertThat(audits.get()).isZero();
        verifyNoInteractions(unusedClock);
    }

    static Stream<String> passwordsBeyondByteLimit() {
        return Stream.of("a".repeat(73), "é".repeat(37), "🔧".repeat(19));
    }

    @ParameterizedTest
    @MethodSource("invalidHashes")
    void refusesMalformedEncoderResultsBeforeObservingDates(String hash) {
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn(hash);
        Clock unusedClock = mock(Clock.class);
        var preparation = new LegalRegistrationPreparation(encoder, unusedClock, () -> AUDIT, 14);
        assertInvalid(() -> preparation.prepare(command("secret-pass"), deadline()));
        verifyNoInteractions(unusedClock);
    }

    static Stream<String> invalidHashes() {
        return Stream.of(null, "", "secret-pass", "$2a$03$" + "A".repeat(53),
                "$2a$32$" + "A".repeat(53), "$2z$10$" + "A".repeat(53), HASH + "A");
    }

    @Test
    void preservesExactRegistrationValuesIncludingEmailBeyondThePhysicalWorkshopLimit() {
        BCryptPasswordEncoder encoder = stubEncoder();
        var registration = new LegalAcceptanceCommand.Registration(" Taller original ", " 123 ",
                " Admin original ", "A".repeat(121) + "@Example.invalid", " secret-pass ");
        var command = LegalAcceptanceCommandValidator.registration(registration, REVISION, List.of());
        var result = preparation(encoder).prepare(command, deadline());
        assertThat(result.registration()).isSameAs(registration);
        assertThat(result.registration().email()).isEqualTo(registration.email());
        assertThat(result.registration().telefonoTaller()).isEqualTo(" 123 ");
        verify(encoder).encode(" secret-pass ");
    }

    @Test
    void separatesTheApplicationDateFromOneCoherentLocalAuditObservation() {
        AtomicInteger observations = new AtomicInteger();
        var preparation = new LegalRegistrationPreparation(stubEncoder(), APPLICATION_CLOCK,
                () -> AUDIT.plusDays(observations.getAndIncrement()), 14);
        var result = preparation.prepare(command("secret-pass"), deadline());
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(result.trialEndDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(result.auditAt()).isEqualTo(LocalDateTime.parse("2026-09-07T22:02:03.123456789"));
        assertThat(observations.get()).isEqualTo(1);
    }

    @Test
    void acceptsTheExistingJvmLocalAuditSupplierWithoutUsingTheTrialClockForAuditing() {
        LocalDateTime before = LocalDateTime.now();
        var result = new LegalRegistrationPreparation(stubEncoder(), APPLICATION_CLOCK,
                LocalDateTime::now, 14).prepare(command("secret-pass"), deadline());
        LocalDateTime after = LocalDateTime.now();
        assertThat(result.auditAt()).isBetween(before, after);
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -14, 1, 28, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void retainsConfiguredTrialDaysIncludingZeroAndNegativeValues(int trialDays) {
        var result = new LegalRegistrationPreparation(stubEncoder(), APPLICATION_CLOCK, () -> AUDIT, trialDays)
                .prepare(command("secret-pass"), deadline());
        assertThat(result.trialEndDate()).isEqualTo(LocalDate.of(2026, 9, 8).plusDays(trialDays));
    }

    @Test
    void appliesCalendarDayArithmeticAcrossLeapDay() {
        Clock leap = Clock.fixed(Instant.parse("2028-02-28T23:59:59Z"), ZoneOffset.UTC);
        var result = new LegalRegistrationPreparation(stubEncoder(), leap, () -> AUDIT, 2)
                .prepare(command("secret-pass"), deadline());
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2028, 2, 28));
        assertThat(result.trialEndDate()).isEqualTo(LocalDate.of(2028, 3, 1));
    }

    @Test
    void rejectsDateOverflowAndMissingAuditBeforeReturningAReusableValue() {
        Clock lastDate = Clock.fixed(LocalDate.MAX.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        AtomicInteger audits = new AtomicInteger();
        var overflowing = new LegalRegistrationPreparation(stubEncoder(), lastDate,
                () -> { audits.incrementAndGet(); return AUDIT; }, 1);
        assertInvalid(() -> overflowing.prepare(command("secret-pass"), deadline()));
        assertThat(audits.get()).isZero();
        assertInvalid(() -> new LegalRegistrationPreparation(stubEncoder(), APPLICATION_CLOCK, () -> null, 14)
                .prepare(command("secret-pass"), deadline()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"encoder", "clock", "audit"})
    void sanitizesCollaboratorFailuresWithoutRetainingInputInCausesOrSuppressedExceptions(String stage) {
        var secretFailure = new IllegalStateException("email private@example.invalid password secret-pass hash " + HASH);
        secretFailure.addSuppressed(new IllegalArgumentException("secret-pass"));
        BCryptPasswordEncoder encoder = stubEncoder();
        Clock clock = APPLICATION_CLOCK;
        Supplier<LocalDateTime> audit = () -> AUDIT;
        if (stage.equals("encoder")) when(encoder.encode(anyString())).thenThrow(secretFailure);
        if (stage.equals("clock")) {
            clock = mock(Clock.class);
            when(clock.instant()).thenThrow(secretFailure);
        }
        if (stage.equals("audit")) audit = () -> { throw secretFailure; };
        var preparation = new LegalRegistrationPreparation(encoder, clock, audit, 14);
        assertInvalid(() -> preparation.prepare(command("secret-pass"), deadline()));
    }

    @Test
    void rejectsAnExpiredBudgetBeforeEncoding() {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        nanos.set(30_000_000_000L);
        BCryptPasswordEncoder encoder = stubEncoder();
        assertThatThrownBy(() -> preparation(encoder).prepare(command("secret-pass"), deadline))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        verifyNoInteractions(encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"encoder", "clock", "audit"})
    void rechecksTheOriginalBudgetAfterEachExternalObservation(String stage) {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        BCryptPasswordEncoder encoder = stubEncoder();
        Clock clock = APPLICATION_CLOCK;
        AtomicInteger auditCalls = new AtomicInteger();
        Supplier<LocalDateTime> audit = () -> {
            auditCalls.incrementAndGet();
            if (stage.equals("audit")) nanos.set(30_000_000_000L);
            return AUDIT;
        };
        if (stage.equals("encoder")) when(encoder.encode(anyString())).thenAnswer(invocation -> {
            nanos.set(30_000_000_000L); return HASH;
        });
        if (stage.equals("clock")) {
            clock = mock(Clock.class);
            when(clock.getZone()).thenReturn(ZoneOffset.UTC);
            when(clock.instant()).thenAnswer(invocation -> { nanos.set(30_000_000_000L); return NOW; });
        }
        var preparation = new LegalRegistrationPreparation(encoder, clock, audit, 14);
        assertThatThrownBy(() -> preparation.prepare(command("secret-pass"), deadline))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThat(auditCalls.get()).isEqualTo(stage.equals("audit") ? 1 : 0);
    }

    @Test
    void deadlineFailureTakesPrecedenceOverACollaboratorFailure() {
        AtomicLong nanos = new AtomicLong();
        var deadline = LegalPrivateRequirementsDeadline.registration(nanos::get);
        BCryptPasswordEncoder encoder = stubEncoder();
        when(encoder.encode(anyString())).thenAnswer(invocation -> {
            nanos.set(30_000_000_000L);
            throw new IllegalStateException("secret-pass");
        });
        assertThatThrownBy(() -> preparation(encoder).prepare(command("secret-pass"), deadline))
                .isInstanceOf(LegalPrivateRequirementsReadException.class).hasNoCause();
    }

    @Test
    void preparedValuesRemainBoundToTheExactCommandAndOriginalBudget() {
        AtomicLong nanos = new AtomicLong();
        var originalBudget = LegalPrivateRequirementsDeadline.registration(nanos::get);
        var command = command("secret-pass");
        var result = preparation(stubEncoder()).prepare(command, originalBudget);
        assertThatCode(() -> result.requireCommand(command)).doesNotThrowAnyException();
        assertInvalid(() -> result.requireCommand(command("secret-pass")));
        assertInvalid(() -> result.requireCommand(command("another-pass")));
        assertInvalid(() -> result.requireCommand(null));
        nanos.set(29_999_999_999L);
        assertThatCode(() -> result.requireCommand(command)).doesNotThrowAnyException();
        nanos.incrementAndGet();
        assertThatThrownBy(() -> result.requireCommand(command))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
        assertThat(deadline().remainingMillis()).isEqualTo(30_000);
        assertThatThrownBy(() -> result.requireCommand(command))
                .isInstanceOf(LegalPrivateRequirementsReadException.class);
    }

    @Test
    void preparedValuesRetainCleanupFailureFromTheirOriginalBudget() {
        var deadline = deadline();
        var command = command("secret-pass");
        var result = preparation(stubEncoder()).prepare(command, deadline);
        var cleanup = new SQLException("synthetic cleanup failure");
        deadline.recordCleanupFailure(cleanup);
        assertThatThrownBy(() -> result.requireCommand(command))
                .isInstanceOf(LegalPrivateRequirementsReadException.class).hasCause(cleanup);
    }

    @Test
    void diagnosticsExposeNeitherRegistrationNorHash() {
        var preparation = preparation(stubEncoder());
        var result = preparation.prepare(command("secret-pass"), deadline());
        assertThat(preparation.toString()).isEqualTo("LegalRegistrationPreparation[redacted]");
        assertThat(result.toString()).isEqualTo("Prepared[redacted]");
        assertThat(result.registration().toString()).isEqualTo("Registration[redacted]");
    }

    private static LegalRegistrationPreparation preparation(BCryptPasswordEncoder encoder) {
        return new LegalRegistrationPreparation(encoder, APPLICATION_CLOCK, () -> AUDIT, 14);
    }

    private static BCryptPasswordEncoder stubEncoder() {
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn(HASH);
        return encoder;
    }

    private static LegalAcceptanceCommand command(String password) {
        return LegalAcceptanceCommandValidator.registration(new LegalAcceptanceCommand.Registration(
                "Taller original", null, "Admin original", "Original@Example.invalid", password), REVISION, List.of());
    }

    private static LegalPrivateRequirementsDeadline deadline() {
        return LegalPrivateRequirementsDeadline.registration(() -> 0L);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(IllegalArgumentException.class).hasMessage(MESSAGE)
                .hasNoCause().satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }
}
