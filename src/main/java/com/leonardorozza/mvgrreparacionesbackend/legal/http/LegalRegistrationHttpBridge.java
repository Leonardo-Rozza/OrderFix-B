package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationBudget;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationFailure;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountVerificationNotifier;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuer;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** One registration route: preserve legacy absence and deliver committed legal identities through K. */
public final class LegalRegistrationHttpBridge {
    private final RegistroService legacy;
    private final Validator validator;
    private final boolean consent;
    private final boolean enforcement;
    private final Supplier<LegalRegistrationService> registration;
    private final Supplier<LegalPublicRequirementsReadService> requirements;
    private final Supplier<LegalRequestMetadataResolver> metadata;
    private final Supplier<LegalRegistrationSessionIssuer> sessions;
    private final AccountVerificationNotifier notifier;
    private final Supplier<LegalRegistrationBudget> budgets;

    public LegalRegistrationHttpBridge(RegistroService legacy, Validator validator, boolean consent,
            boolean enforcement, Supplier<LegalRegistrationService> registration,
            Supplier<LegalPublicRequirementsReadService> requirements, Supplier<LegalRequestMetadataResolver> metadata,
            Supplier<LegalRegistrationSessionIssuer> sessions, AccountVerificationNotifier notifier) {
        this(legacy, validator, consent, enforcement, registration, requirements, metadata, sessions, notifier,
                LegalRegistrationBudget::start);
    }

    LegalRegistrationHttpBridge(RegistroService legacy, Validator validator, boolean consent,
            boolean enforcement, Supplier<LegalRegistrationService> registration,
            Supplier<LegalPublicRequirementsReadService> requirements, Supplier<LegalRequestMetadataResolver> metadata,
            Supplier<LegalRegistrationSessionIssuer> sessions, AccountVerificationNotifier notifier,
            Supplier<LegalRegistrationBudget> budgets) {
        this.legacy = Objects.requireNonNull(legacy);
        this.validator = Objects.requireNonNull(validator);
        this.consent = consent;
        this.enforcement = enforcement;
        if (enforcement && !consent) throw new IllegalArgumentException("El registro obligatorio requiere consentimiento");
        this.registration = Objects.requireNonNull(registration);
        this.requirements = Objects.requireNonNull(requirements);
        this.metadata = Objects.requireNonNull(metadata);
        this.sessions = Objects.requireNonNull(sessions);
        this.notifier = Objects.requireNonNull(notifier);
        this.budgets = Objects.requireNonNull(budgets);
    }

    public AuthResponseDto register(HttpServletRequest request) {
        // Start before parsing even if only the body later reveals a legal attempt. Never impose
        // this deadline on genuinely absent legacy requests when enforcement remains disabled.
        final LegalRegistrationBudget owner;
        final List<String> headers;
        try {
            Objects.requireNonNull(request);
            owner = Objects.requireNonNull(budgets.get());
            headers = atMostTwo(request.getHeaders("Idempotency-Key"));
        } catch (RuntimeException observationFailure) {
            throw LegalRegistrationHttpException.unavailable(observationFailure);
        }
        boolean bounded = enforcement || headers == null || !headers.isEmpty();
        final LegalRegistrationRequests.Parsed parsed;
        try {
            parsed = LegalRegistrationRequests.read(headers, lazyBody(request), validator,
                    bounded ? owner::check : () -> { });
        } catch (LegalRegistrationHttpException rejection) {
            if (bounded) check(owner);
            throw rejection;
        } catch (RuntimeException failure) {
            if (bounded) check(owner);
            throw LegalRegistrationHttpException.unavailable(failure);
        }
        if (parsed.kind() == LegalRegistrationRequests.Kind.ABSENT && !enforcement) {
            return legacy.registrar(parsed.registration());
        }
        final AuthResponseDto result;
        final boolean created;
        final long userId;
        final long tallerId;
        try {
            owner.check();
            if (parsed.kind() == LegalRegistrationRequests.Kind.ABSENT) {
                throw LegalRegistrationHttpException.requiredAcceptance(
                        Objects.requireNonNull(requirements.get()).readRegistration(owner));
            }
            if (!consent) throw LegalRegistrationHttpException.unavailable(null);
            var captured = Objects.requireNonNull(metadata.get()).resolve(request);
            owner.check();
            var dto = parsed.registration();
            var input = new Registration(dto.nombreTaller(), dto.telefonoTaller(), dto.nombreAdmin(),
                    dto.email(), dto.password());
            var receipt = Objects.requireNonNull(Objects.requireNonNull(registration.get()).register(input,
                    parsed.idempotencyKey(), parsed.requiredSetRevision(), parsed.acceptances(), captured, owner));
            owner.check();
            userId = receipt.userId();
            tallerId = receipt.tallerId();
            created = !receipt.replay();
            result = Objects.requireNonNull(Objects.requireNonNull(sessions.get())
                    .issueSession(userId, tallerId, dto.password(), owner));
            owner.check();
        } catch (LegalRegistrationHttpException rejection) {
            check(owner);
            throw rejection;
        } catch (LegalRegistrationFailure failure) {
            check(owner);
            throw LegalRegistrationHttpException.from(failure);
        } catch (BadCredentialsException rejected) {
            check(owner);
            throw LegalRegistrationHttpException.authenticationRejected(rejected);
        } catch (RuntimeException failure) {
            check(owner);
            throw LegalRegistrationHttpException.unavailable(failure);
        }
        // Existing best-effort email is deliberately outside the bounded legal/session phase.
        // It neither determines persistence nor repeats on replay. No deadline restarts here.
        if (created) notifier.notifyVerification(userId, tallerId);
        return result;
    }

    private static void check(LegalRegistrationBudget owner) {
        try { owner.check(); }
        catch (RuntimeException unavailable) { throw LegalRegistrationHttpException.unavailable(unavailable); }
    }

    private static InputStream lazyBody(HttpServletRequest request) {
        return new InputStream() {
            private InputStream delegate;
            private InputStream opened() throws IOException {
                if (delegate == null) {
                    requireJson(request);
                    delegate = Objects.requireNonNull(request.getInputStream());
                }
                return delegate;
            }
            @Override public int read() throws IOException { return opened().read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                return opened().read(bytes, offset, length);
            }
            // Servlet owns the stream. Query parameters remain ignored as in the legacy route.
        };
    }

    private static void requireJson(HttpServletRequest request) {
        try {
            var values = atMostTwo(request.getHeaders(HttpHeaders.CONTENT_TYPE));
            if (values == null || values.size() != 1 || values.getFirst() == null) throw new IllegalArgumentException();
            var type = MediaType.parseMediaType(values.getFirst());
            String subtype = type.getSubtype();
            if (!"application".equalsIgnoreCase(type.getType())
                    || !("json".equalsIgnoreCase(subtype) || subtype.toLowerCase(java.util.Locale.ROOT).endsWith("+json"))
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw LegalRegistrationHttpException.unsupportedMediaType();
        }
    }

    private static List<String> atMostTwo(Enumeration<String> source) {
        if (source == null) return null;
        List<String> result = new ArrayList<>(2);
        while (result.size() < 2 && source.hasMoreElements()) result.add(source.nextElement());
        return result;
    }
}
