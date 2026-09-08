package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Document;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalPublicRegistrationRequirements;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequiredSetProjection.RequirementProjection;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceValidationException.Motivo;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure registration selection after replay and independently accredited current availability. */
final class LegalRegistrationSelection {

    /** Only this selector can issue a token; the writer must bind it to the reservation's command. */
    static final class Selection {
        private final LegalAcceptanceCommand command;
        private final LegalPublicRegistrationRequirements current;
        private final List<RequirementProjection> requirements;

        private Selection(LegalAcceptanceCommand command, LegalPublicRegistrationRequirements current,
                          List<RequirementProjection> requirements) {
            require(!requirements.isEmpty());
            this.command = command;
            this.current = current;
            this.requirements = List.copyOf(requirements);
        }

        void requireCommand(LegalAcceptanceCommand candidate) { require(command == candidate); }
        LegalPublicRegistrationRequirements current() { return current; }
        List<RequirementProjection> requirements() { return requirements; }

        @Override public String toString() { return "LegalRegistrationSelection[redacted]"; }
    }

    Selection select(LegalAcceptanceCommand command, LegalPublicRegistrationRequirements current) {
        require(command != null && current != null
                && command.operation() == LegalAcceptanceCommand.Operation.REGISTRATION
                && command.actor() == null && command.registration() != null);
        // Registration has no existing actor/evidence: even an empty or duplicate request must
        // compare revisions before reporting any semantic defect. There is no dedup shortcut.
        if (!command.requiredSetRevision().equals(current.requiredSetRevision())) {
            throw LegalRegistrationValidationException.stale(command.requiredSetRevision(), current);
        }

        Map<UUID, RequirementProjection> currentById = new HashMap<>();
        current.projection().requirements().forEach(value -> currentById.put(value.versionId(), value));
        Set<UUID> submittedIds = new HashSet<>();
        EnumSet<Motivo> motivos = EnumSet.noneOf(Motivo.class);
        for (Acceptance acceptance : command.acceptances()) {
            if (!submittedIds.add(acceptance.requisitoVersionId())) motivos.add(Motivo.REQUISITO_DUPLICADO);
            Set<UUID> documentIds = new HashSet<>();
            for (Document document : acceptance.documentos()) {
                if (!documentIds.add(document.documentoVersionId())) motivos.add(Motivo.DOCUMENTO_DUPLICADO);
            }
            if (!acceptance.confirmado()) motivos.add(Motivo.CONFIRMACION_REQUERIDA);
            RequirementProjection requirement = currentById.get(acceptance.requisitoVersionId());
            if (requirement == null) {
                motivos.add(Motivo.REQUISITO_NO_PERTENECE_AL_CONJUNTO);
                continue;
            }
            if (acceptance.tipoActo() != requirement.actType()) motivos.add(Motivo.ACTO_NO_COINCIDE);
            if (!acceptance.afirmacionSha256().equals(requirement.statementSha256())) motivos.add(Motivo.DIGEST_NO_COINCIDE);
            Map<UUID, String> expected = new HashMap<>();
            requirement.documents().forEach(value -> expected.put(value.versionId(), value.sha256()));
            for (Document document : acceptance.documentos()) {
                String digest = expected.get(document.documentoVersionId());
                if (digest == null) motivos.add(Motivo.DOCUMENTO_NO_PERTENECE_AL_REQUISITO);
                else if (!digest.equals(document.sha256())) motivos.add(Motivo.DIGEST_NO_COINCIDE);
            }
            if (!documentIds.containsAll(expected.keySet())) motivos.add(Motivo.DOCUMENTO_FALTANTE);
        }
        for (RequirementProjection requirement : current.projection().requirements()) {
            if (requirement.required() && !submittedIds.contains(requirement.versionId())) motivos.add(Motivo.REQUISITO_FALTANTE);
        }
        if (!motivos.isEmpty()) throw LegalRegistrationValidationException.invalid(motivos);

        return new Selection(command, current, current.projection().requirements().stream()
                .filter(value -> submittedIds.contains(value.versionId())).toList());
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("La selección de registro legal no es válida.");
    }
}
