package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.RegisterRequestDto;
import org.springframework.stereotype.Service;

/** Historical onboarding, followed by verification and a current session from committed account IDs. */
@Service
public class RegistroService {
    private final LegacyRegistrationAccountWriter writer;
    private final AccountVerificationNotifier notifier;
    private final AccountSessionPolicy sessionPolicy;

    public RegistroService(LegacyRegistrationAccountWriter writer, AccountVerificationNotifier notifier,
                           AccountSessionPolicy sessionPolicy) {
        this.writer = writer;
        this.notifier = notifier;
        this.sessionPolicy = sessionPolicy;
    }

    public AuthResponseDto registrar(RegisterRequestDto request) {
        var identity = writer.create(request);
        notifier.notifyVerification(identity.userId(), identity.tallerId());
        return sessionPolicy.issueSession(identity.userId(), identity.tallerId(), request.password());
    }
}
