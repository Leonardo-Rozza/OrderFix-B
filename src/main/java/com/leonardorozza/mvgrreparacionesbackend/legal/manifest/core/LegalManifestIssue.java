package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Issue seguro y determinista expuesto por el núcleo legal.
 */
public final class LegalManifestIssue implements Comparable<LegalManifestIssue> {

    private static final int MAX_LOCATION_LENGTH = 512;
    private static final Pattern SAFE_LOCATION =
            Pattern.compile("[A-Za-z0-9_./,:#~-]+");

    public static final Comparator<LegalManifestIssue> ORDERING = Comparator
            .comparingInt((LegalManifestIssue issue) -> -issue.severity().precedence())
            .thenComparing(issue -> issue.code().name())
            .thenComparing(LegalManifestIssue::location);

    private final LegalManifestStatus severity;
    private final LegalManifestIssueCode code;
    private final String location;
    private final String message;

    private LegalManifestIssue(LegalManifestIssueCode code, String location) {
        this.code = Objects.requireNonNull(code, "code");
        this.severity = code.severity();
        this.location = requireSafeLocation(location);
        this.message = code.safeMessage();
    }

    public static LegalManifestIssue at(LegalManifestIssueCode code, String location) {
        return new LegalManifestIssue(code, location);
    }

    public LegalManifestStatus severity() {
        return severity;
    }

    public LegalManifestIssueCode code() {
        return code;
    }

    public String location() {
        return location;
    }

    public String message() {
        return message;
    }

    @Override
    public int compareTo(LegalManifestIssue other) {
        return ORDERING.compare(this, other);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LegalManifestIssue issue)) {
            return false;
        }
        return severity == issue.severity
                && code == issue.code
                && location.equals(issue.location)
                && message.equals(issue.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, code, location, message);
    }

    @Override
    public String toString() {
        return "LegalManifestIssue[severity=" + severity
                + ", code=" + code
                + ", location=" + location
                + ", message=" + message + ']';
    }

    private static String requireSafeLocation(String candidate) {
        Objects.requireNonNull(candidate, "location");
        if (candidate.isBlank()
                || candidate.length() > MAX_LOCATION_LENGTH
                || candidate.startsWith("/")
                || candidate.startsWith("\\")
                || candidate.indexOf('\\') >= 0
                || candidate.contains("://")
                || isWindowsAbsolute(candidate)
                || !SAFE_LOCATION.matcher(candidate).matches()) {
            throw new IllegalArgumentException("La location del issue debe ser relativa y segura");
        }

        for (String segment : candidate.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("La location del issue no puede escapar su raíz");
            }
        }
        return candidate;
    }

    private static boolean isWindowsAbsolute(String candidate) {
        return candidate.length() >= 2
                && Character.isLetter(candidate.charAt(0))
                && candidate.charAt(1) == ':';
    }
}
