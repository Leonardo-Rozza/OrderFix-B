package com.leonardorozza.mvgrreparacionesbackend.photos;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalAcceptanceCommand.Acceptance;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsResponses.Requisito;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.MomentoFoto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PrivatePhotoDtos {
    private PrivatePhotoDtos() { }
    public record Limits(int maxBytes, List<String> mimeTypes, int maxFotos) {
        public Limits { mimeTypes = List.copyOf(mimeTypes); }
    }
    public record Requirements(String locale, String requiredSetRevision, List<Requisito> requisitos, Limits limites) {
        public Requirements { requisitos = List.copyOf(requisitos); }
    }
    public record Attestation(String tipo, List<String> alcances, boolean confirmada) { }
    public record Create(String nombre, String mimeType, long bytes, String sha256, MomentoFoto momento,
                         String requiredSetRevision, List<Acceptance> aceptacionesLegales, Attestation atestacion) {
        @Override public String toString() { return "PhotoCreate[redacted]"; }
    }
    public record Upload(String method, String url) { }
    public record Intention(UUID id, String estado, Instant expiresAt, Upload upload) { }
    public record Photo(UUID id, MomentoFoto momento, String mimeType, long bytes, String sha256, Instant createdAt) { }
    public record Result<T>(T value, boolean reused) { }
    public record Content(String mimeType, byte[] bytes) {
        @Override public String toString() { return "PhotoContent[redacted]"; }
    }
}
