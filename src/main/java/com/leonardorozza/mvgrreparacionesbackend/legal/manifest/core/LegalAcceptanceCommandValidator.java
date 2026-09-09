package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Operation;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Registration;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure shape and size validation only. Duplicates, empty lists and false confirmations are retained
 * so replay, revision and semantic checks can keep their contractual priority in later stages.
 */
public final class LegalAcceptanceCommandValidator {
    public static final int MAX_ACCEPTANCES = 2_048;
    public static final int MAX_DOCUMENTS_PER_ACCEPTANCE = 16;
    private static final int MAX_EMAIL_UTF8_BYTES = 1_048_576;
    private static final Pattern REVISION = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Comparator<UUID> UUID_ORDER = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };
    private static final Comparator<Document> DOCUMENT_ORDER = Comparator
            .comparing(Document::documentoVersionId, UUID_ORDER).thenComparing(Document::sha256);
    private static final Comparator<Acceptance> ACCEPTANCE_ORDER = Comparator
            .comparing(Acceptance::requisitoVersionId, UUID_ORDER)
            .thenComparing(acceptance -> acceptance.tipoActo().name())
            .thenComparing(Acceptance::afirmacionSha256)
            .thenComparing(Acceptance::documentos, LegalAcceptanceCommandValidator::compareDocuments)
            .thenComparing(Acceptance::confirmado);

    private LegalAcceptanceCommandValidator() { }

    public static LegalAcceptanceCommand authenticated(LegalActorSnapshot actor, String requiredSetRevision,
                                                         List<Acceptance> acceptances) {
        if (actor == null || (actor.role() != UserRole.ADMIN && actor.role() != UserRole.USER)) throw invalid();
        // This tests the supplied snapshot's state, without claiming a server/database observation.
        try { actor.requireEnabled(); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        return new LegalAcceptanceCommand(Operation.AUTHENTICATED_ACCEPTANCE, actor, null,
                revision(requiredSetRevision), canonicalAcceptances(acceptances));
    }

    /** Photo manifest is part of the protected operation, never authority chosen by the browser. */
    public static LegalAcceptanceCommand photo(LegalActorSnapshot actor, String revision,
            List<Acceptance> acceptances, LegalAcceptanceCommand.PhotoContext photo) {
        LegalAcceptanceCommand canonical=authenticated(actor,revision,acceptances);
        if(photo==null || photo.reparacionId()<=0 || photo.bytes()<12 || photo.bytes()>8_000_000
                || !List.of("image/jpeg","image/png").contains(photo.mimeType())
                || !List.of("INGRESO","POST_REPARACION").contains(photo.momento())) throw invalid();
        requiredText(photo.nombre(),1,255); digest(photo.sha256());
        if(photo.nombre().codePoints().anyMatch(c->c<32 || (c>=127 && c<=159))) throw invalid();
        return new LegalAcceptanceCommand(Operation.AUTHENTICATED_ACCEPTANCE,actor,null,
                canonical.requiredSetRevision(),canonical.acceptances(),photo);
    }

    public static LegalAcceptanceCommand registration(Registration registration, String requiredSetRevision,
                                                        List<Acceptance> acceptances) {
        if (registration == null) throw invalid();
        requiredText(registration.nombreTaller(), 1, 120);
        optionalText(registration.telefonoTaller(), 20);
        requiredText(registration.nombreAdmin(), 1, 50);
        requiredText(registration.password(), 6, 100);
        String email = registration.email();
        if (email == null || email.length() > MAX_EMAIL_UTF8_BYTES || email.isBlank()) throw invalid();
        // HTTP still owns @Email. A defensive byte limit does not introduce a 120-character rule.
        if (utf8Length(email) > MAX_EMAIL_UTF8_BYTES) throw invalid();
        return new LegalAcceptanceCommand(Operation.REGISTRATION, null, registration,
                revision(requiredSetRevision), canonicalAcceptances(acceptances));
    }

    /** Only canonical lowercase UUID v4 with the RFC variant; never retains the untrusted source in a cause. */
    public static UUID requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) throw invalid();
        return UUID.fromString(value);
    }

    private static String revision(String value) {
        if (value == null || !REVISION.matcher(value).matches()) throw invalid();
        return value;
    }

    private static String digest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) throw invalid();
        return value;
    }

    private static List<Acceptance> canonicalAcceptances(List<Acceptance> source) {
        if (source == null || source.size() > MAX_ACCEPTANCES) throw invalid();
        List<Acceptance> result = new ArrayList<>(source.size());
        for (Acceptance acceptance : source) {
            if (acceptance == null || acceptance.requisitoVersionId() == null || acceptance.tipoActo() == null
                    || acceptance.documentos() == null || acceptance.documentos().size() > MAX_DOCUMENTS_PER_ACCEPTANCE) {
                throw invalid();
            }
            List<Document> documents = new ArrayList<>(acceptance.documentos().size());
            for (Document document : acceptance.documentos()) {
                if (document == null || document.documentoVersionId() == null) throw invalid();
                digest(document.sha256());
                documents.add(document);
            }
            documents.sort(DOCUMENT_ORDER);
            result.add(new Acceptance(acceptance.requisitoVersionId(), acceptance.tipoActo(),
                    digest(acceptance.afirmacionSha256()), documents, acceptance.confirmado()));
        }
        result.sort(ACCEPTANCE_ORDER);
        return List.copyOf(result);
    }

    private static int compareDocuments(List<Document> left, List<Document> right) {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int compared = DOCUMENT_ORDER.compare(left.get(index), right.get(index));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static void requiredText(String value, int minimum, int maximum) {
        if (value == null || value.isBlank() || value.length() < minimum || value.length() > maximum) throw invalid();
        utf8Length(value);
    }

    private static void optionalText(String value, int maximum) {
        if (value == null) return;
        if (value.length() > maximum) throw invalid();
        utf8Length(value);
    }

    /** Reject malformed UTF-16 without replacement or normalization; count original UTF-8 bytes. */
    private static long utf8Length(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) throw invalid();
                index++;
                bytes += 4;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid();
            } else {
                bytes += current <= 0x7f ? 1 : current <= 0x7ff ? 2 : 3;
            }
        }
        return bytes;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("El comando legal no tiene un formato válido.");
    }
}
