package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Operation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoActoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalAcceptanceCommandTest {
    private static final String REVISION = "sha256:" + "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);

    @Test void authenticatedCommandHasOnlyTheActorBranchAndExactBusinessValues() {
        var actor = new LegalActorSnapshot(51, 72, UserRole.ADMIN, 7, true, true);
        var acceptance = acceptance(new ArrayList<>(List.of(document())));
        var command = LegalAcceptanceCommandValidator.authenticated(actor, REVISION, List.of(acceptance));

        assertThat(command.operation()).isEqualTo(Operation.AUTHENTICATED_ACCEPTANCE);
        assertThat(command.actor()).isSameAs(actor);
        assertThat(command.registration()).isNull();
        assertThat(command.requiredSetRevision()).isSameAs(REVISION);
        assertThat(command.acceptances()).containsExactly(acceptance);
        assertThat(command.acceptances().getFirst().confirmado()).isFalse();
    }

    @Test void registrationCommandHasOnlyTheRegistrationBranchAndRetainsExactStrings() {
        var registration = new Registration(" Taller e\u0301 ", null, " ADMIN ", "Nombre+Tag@Example.INVALID ", " secret-value ");
        var command = LegalAcceptanceCommandValidator.registration(registration, REVISION, List.of());

        assertThat(command.operation()).isEqualTo(Operation.REGISTRATION);
        assertThat(command.actor()).isNull();
        assertThat(command.registration()).isSameAs(registration);
        assertThat(command.registration().nombreTaller()).isEqualTo(" Taller e\u0301 ");
        assertThat(command.registration().nombreAdmin()).isEqualTo(" ADMIN ");
        assertThat(command.registration().email()).isEqualTo("Nombre+Tag@Example.INVALID ");
        assertThat(command.registration().password()).isEqualTo(" secret-value ");
        assertThat(command.registration().telefonoTaller()).isNull();
        assertThat(command.acceptances()).isEmpty();
    }

    @Test void everyListIsCopiedAndUnmodifiableBeforeAndAfterValidation() {
        var originalDocument = document();
        List<Document> documents = new ArrayList<>(List.of(originalDocument));
        Acceptance raw = acceptance(documents);
        documents.clear();
        assertThat(raw.documentos()).containsExactly(originalDocument);
        assertThatThrownBy(() -> raw.documentos().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<Acceptance> source = new ArrayList<>(List.of(raw));
        var command = LegalAcceptanceCommandValidator.authenticated(actor(), REVISION, source);
        source.clear();
        assertThat(command.acceptances()).containsExactly(raw);
        assertThat(command.acceptances().getFirst().documentos()).containsExactly(originalDocument);
        assertThatThrownBy(() -> command.acceptances().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.acceptances().getFirst().documentos().add(document()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void canonicalizationNeverMutatesTheCallersOrder() {
        var high = new Document(new UUID(-1, -1), DIGEST);
        var low = new Document(new UUID(0, 1), DIGEST);
        List<Document> documents = new ArrayList<>(List.of(high, low));
        Acceptance highAcceptance = new Acceptance(new UUID(-1, -1), TipoActoLegal.ACEPTACION, DIGEST, documents, true);
        Acceptance lowAcceptance = new Acceptance(new UUID(0, 1), TipoActoLegal.ACEPTACION, DIGEST, List.of(), false);
        List<Acceptance> source = new ArrayList<>(List.of(highAcceptance, lowAcceptance));

        var command = LegalAcceptanceCommandValidator.authenticated(actor(), REVISION, source);

        assertThat(source).containsExactly(highAcceptance, lowAcceptance);
        assertThat(documents).containsExactly(high, low);
        assertThat(highAcceptance.documentos()).containsExactly(high, low);
        assertThat(command.acceptances().getFirst()).isEqualTo(lowAcceptance);
        assertThat(command.acceptances().getLast().documentos()).containsExactly(low, high);
    }

    @Test void constructionIsNotPublicAndTheCommandHasNoMutableInstanceFields() {
        assertThat(Modifier.isFinal(LegalAcceptanceCommand.class.getModifiers())).isTrue();
        assertThat(LegalAcceptanceCommand.class.getConstructors()).isEmpty();
        assertThat(LegalAcceptanceCommand.class.getDeclaredConstructors()).hasSize(2);
        for (var constructor : LegalAcceptanceCommand.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isFalse();
            assertThat(Modifier.isProtected(constructor.getModifiers())).isFalse();
        }
        for (var field : LegalAcceptanceCommand.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isPrivate(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            }
        }
    }

    @Test void diagnosticsNeverRevealRegistrationActorOrEvidenceInputs() {
        Registration registration = new Registration("private-workshop", "+private-phone", "private-admin",
                "private-email@example.invalid", "private-password");
        var command = LegalAcceptanceCommandValidator.registration(registration, REVISION, List.of(acceptance(List.of(document()))));
        var authenticated = LegalAcceptanceCommandValidator.authenticated(actor(), REVISION, command.acceptances());
        assertThat(command.toString()).isEqualTo("LegalAcceptanceCommand[redacted]");
        assertThat(authenticated.toString()).isEqualTo("LegalAcceptanceCommand[redacted]");
        assertThat(registration.toString()).isEqualTo("Registration[redacted]");
        assertThat(command.acceptances().getFirst().toString()).isEqualTo("Acceptance[redacted]");
        assertThat(command.acceptances().getFirst().documentos().getFirst().toString()).isEqualTo("Document[redacted]");
        assertThat(String.join(" ", command.toString(), authenticated.toString(), registration.toString()))
                .doesNotContain("private-", REVISION, DIGEST, "userId", "tallerId", "tokenVersion");
    }

    private static LegalActorSnapshot actor() { return new LegalActorSnapshot(51, 72, UserRole.USER, 7, true, true); }
    private static Document document() { return new Document(new UUID(0, 201), DIGEST); }
    private static Acceptance acceptance(List<Document> documents) {
        return new Acceptance(new UUID(0, 101), TipoActoLegal.ACEPTACION, DIGEST, documents, false);
    }
}
