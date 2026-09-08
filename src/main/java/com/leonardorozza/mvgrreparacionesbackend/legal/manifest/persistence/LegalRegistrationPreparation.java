package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Prepares account values before business INSERTs without opening a connection or resetting the operation budget. */
final class LegalRegistrationPreparation {
    private static final Pattern BCRYPT = Pattern.compile(
            "\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./0-9A-Za-z]{53}");

    private final BCryptPasswordEncoder passwords;
    private final Clock applicationClock;
    private final Supplier<LocalDateTime> auditNow;
    private final int trialDays;

    LegalRegistrationPreparation(BCryptPasswordEncoder passwords, Clock applicationClock,
                                 Supplier<LocalDateTime> auditNow, int trialDays) {
        require(passwords != null && applicationClock != null && auditNow != null);
        this.passwords = passwords;
        this.applicationClock = applicationClock;
        this.auditNow = auditNow;
        // The existing registration configuration also accepts zero and negative trial lengths.
        this.trialDays = trialDays;
    }

    Prepared prepare(LegalAcceptanceCommand command, LegalPrivateRequirementsDeadline deadline) {
        require(command != null && command.operation() == LegalAcceptanceCommand.Operation.REGISTRATION
                && command.registration() != null && deadline != null);
        String encodedPassword = observe(deadline, () -> passwords.encode(command.registration().password()));
        require(encodedPassword != null && BCRYPT.matcher(encodedPassword).matches());
        LocalDate startDate = observe(deadline, () -> LocalDate.now(applicationClock));
        LocalDate trialEndDate = observe(deadline, () -> startDate.plusDays(trialDays));
        LocalDateTime auditAt = observe(deadline, auditNow);
        require(auditAt != null);
        return new Prepared(command, deadline, encodedPassword, startDate, trialEndDate, auditAt);
    }

    private static <T> T observe(LegalPrivateRequirementsDeadline deadline, Supplier<T> observation) {
        deadline.check();
        T result;
        try {
            result = observation.get();
        } catch (RuntimeException failure) {
            // A spent budget or failed cleanup takes precedence; collaborator diagnostics may contain input.
            deadline.check();
            throw invalid();
        }
        deadline.check();
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("La preparación de registro no es válida.");
    }

    @Override public String toString() { return "LegalRegistrationPreparation[redacted]"; }

    /** Construction is private; the immutable command owns the only reference to the original password. */
    static final class Prepared {
        private final LegalAcceptanceCommand command;
        private final LegalPrivateRequirementsDeadline deadline;
        private final String encodedPassword;
        private final LocalDate startDate;
        private final LocalDate trialEndDate;
        private final LocalDateTime auditAt;

        private Prepared(LegalAcceptanceCommand command, LegalPrivateRequirementsDeadline deadline,
                         String encodedPassword, LocalDate startDate, LocalDate trialEndDate,
                         LocalDateTime auditAt) {
            this.command = command;
            this.deadline = deadline;
            this.encodedPassword = encodedPassword;
            this.startDate = startDate;
            this.trialEndDate = trialEndDate;
            this.auditAt = auditAt;
        }

        LegalAcceptanceCommand.Registration registration() { return command.registration(); }
        String encodedPassword() { return encodedPassword; }
        LocalDate startDate() { return startDate; }
        LocalDate trialEndDate() { return trialEndDate; }
        LocalDateTime auditAt() { return auditAt; }

        void requireCommand(LegalAcceptanceCommand candidate) {
            deadline.check();
            require(candidate == command);
        }

        @Override public String toString() { return "Prepared[redacted]"; }
    }
}
