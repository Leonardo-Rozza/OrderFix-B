package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Detecta placeholders y marcadores editoriales no publicables coordinados con el frontend.
 */
public final class LegalEditorialMarkerValidator {

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .maxOpenBlockParsers(LegalMarkdownValidator.MAX_COMMONMARK_BLOCK_PARSERS)
            .maxInlineNesting(LegalMarkdownValidator.MAX_COMMONMARK_INLINE_NESTING)
            .build();
    private static final List<String> KNOWN_PLACEHOLDERS = List.of(
            "[RAZÓN SOCIAL]",
            "[CUIT]",
            "[DOMICILIO]",
            "[EMAIL LEGAL]",
            "[EMAIL PRIVACIDAD]",
            "[JURISDICCIÓN]",
            "[HORARIO DE ATENCIÓN]");
    private static final List<String> NORMALIZED_KNOWN_PLACEHOLDERS = KNOWN_PLACEHOLDERS.stream()
            .map(LegalEditorialMarkerValidator::normalizeTypography)
            .toList();

    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile(
            "\\{\\{[^{}]+}}|<REPLACE_ME>|\\bTODO_LEGAL\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GENERIC_BRACKET_PLACEHOLDER = Pattern.compile(
            "\\[(?:RAZON|CUIT|DOMICILIO|EMAIL|JURISDICCION|HORARIO|REEMPLAZAR|"
                    + "COMPLETAR|PENDIENTE)[^]]*]");
    private static final Pattern EDITORIAL_MARKER = Pattern.compile(
            "\\bBORRADOR\\b|NO PUBLICAR|NO COBRAR|"
                    + "(?:VERSION|VIGENCIA)[^\\r\\n]{0,40}\\b(?:NO ASIGNAD[AO]|NO DEFINID[AO])\\b|"
                    + "(?:RESPONSABLE|PRESTADOR|PROVEEDOR)[^\\r\\n]{0,40}\\bA COMPLETAR\\b|"
                    + "PENDIENTE DE REVISION");
    private static final Pattern SOFT_BREAK_EDITORIAL_MARKER = Pattern.compile(
            "\\bBORRADOR\\b|NO PUBLICAR|NO COBRAR|PENDIENTE DE REVISION");

    /**
     * Devuelve el contenido sin modificar únicamente cuando no conserva marcadores editoriales.
     */
    public LegalManifestValidation<CleanLegalText> validate(String content, String location) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(location, "location");

        // Valida la location también cuando el contenido está limpio.
        LegalManifestIssue placeholderIssue = LegalManifestIssue.at(
                LegalManifestIssueCode.LEGAL_PLACEHOLDER_FOUND,
                location);
        List<LegalManifestIssue> issues = new ArrayList<>(2);

        Node document = PARSER.parse(content);
        VisibleText visibleText = visibleText(document, true);
        VisibleText visibleTextWithoutLinkLabels = visibleText(document, false);
        String normalized = normalizeTypography(content);
        String normalizedVisible = normalizeTypography(visibleText.softBreaksAsSpaces());
        String normalizedGenericVisible = normalizeTypography(
                visibleTextWithoutLinkLabels.softBreaksAsSpaces());
        String withoutMarkdownBrackets = stripMarkdownLabels(document, content);
        boolean knownPlaceholder = NORMALIZED_KNOWN_PLACEHOLDERS.stream()
                .anyMatch(placeholder -> normalized.contains(placeholder)
                        || normalizedVisible.contains(placeholder));
        boolean templatePlaceholder = TEMPLATE_PLACEHOLDER.matcher(normalized).find()
                || TEMPLATE_PLACEHOLDER.matcher(normalizedVisible).find();
        boolean genericPlaceholder = GENERIC_BRACKET_PLACEHOLDER
                .matcher(normalizeTypography(withoutMarkdownBrackets))
                .find()
                || GENERIC_BRACKET_PLACEHOLDER.matcher(normalizedGenericVisible).find();
        if (knownPlaceholder || templatePlaceholder || genericPlaceholder) {
            issues.add(placeholderIssue);
        }

        boolean editorialMarker = EDITORIAL_MARKER
                .matcher(normalizeTypographyPreservingLines(content))
                .find()
                || EDITORIAL_MARKER
                .matcher(normalizeTypographyPreservingLines(visibleText.sourceLines()))
                .find()
                || SOFT_BREAK_EDITORIAL_MARKER
                .matcher(normalizeTypographyPreservingLines(visibleText.softBreaksAsSpaces()))
                .find();
        if (editorialMarker) {
            issues.add(LegalManifestIssue.at(
                    LegalManifestIssueCode.LEGAL_EDITORIAL_MARKER_FOUND,
                    location));
        }

        if (!issues.isEmpty()) {
            return LegalManifestValidation.failure(issues);
        }
        return LegalManifestValidation.pass(new CleanLegalText(content));
    }

    private static String stripMarkdownLabels(Node document, String content) {
        char[] sanitized = content.toCharArray();
        maskCheckboxes(sanitized);
        maskAstLabels(document, content, sanitized);
        return new String(sanitized);
    }

    private static VisibleText visibleText(Node document, boolean includeLinkLabels) {
        StringBuilder sourceLines = new StringBuilder();
        StringBuilder softBreaksAsSpaces = new StringBuilder();
        appendVisibleText(
                document,
                sourceLines,
                softBreaksAsSpaces,
                includeLinkLabels);
        return new VisibleText(sourceLines.toString(), softBreaksAsSpaces.toString());
    }

    private static void appendVisibleText(
            Node node,
            StringBuilder sourceLines,
            StringBuilder softBreaksAsSpaces,
            boolean includeLinkLabels) {
        if (!includeLinkLabels && (node instanceof Link || node instanceof Image)) {
            return;
        }
        if (node instanceof Text text) {
            appendBoth(text.getLiteral(), sourceLines, softBreaksAsSpaces);
            return;
        }
        if (node instanceof Code code) {
            appendBoth(code.getLiteral(), sourceLines, softBreaksAsSpaces);
            return;
        }
        if (node instanceof SoftLineBreak) {
            sourceLines.append('\n');
            softBreaksAsSpaces.append(' ');
            return;
        }
        if (node instanceof HardLineBreak) {
            appendBoth("\n", sourceLines, softBreaksAsSpaces);
            return;
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendVisibleText(
                    child,
                    sourceLines,
                    softBreaksAsSpaces,
                    includeLinkLabels);
        }
        if (node instanceof Block) {
            appendBoth("\n", sourceLines, softBreaksAsSpaces);
        }
    }

    private static void appendBoth(
            String value,
            StringBuilder sourceLines,
            StringBuilder softBreaksAsSpaces) {
        sourceLines.append(value);
        softBreaksAsSpaces.append(value);
    }

    private static void maskAstLabels(Node node, String source, char[] sanitized) {
        if (node instanceof Link || node instanceof Image) {
            if (isBracketLabeled(node, source)) {
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    maskNodeTree(child, sanitized);
                }
            }
        } else if (node instanceof LinkReferenceDefinition) {
            maskReferenceDefinitionLabel(node, source, sanitized);
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            maskAstLabels(child, source, sanitized);
        }
    }

    private static boolean isBracketLabeled(Node node, String source) {
        SourceBounds bounds = sourceBounds(node, source.length());
        if (bounds == null) {
            return false;
        }
        String raw = source.substring(bounds.start(), bounds.end());
        return raw.startsWith("[") || raw.startsWith("![");
    }

    private static void maskNodeTree(Node node, char[] sanitized) {
        maskSourceSpans(node, sanitized);
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            maskNodeTree(child, sanitized);
        }
    }

    private static void maskReferenceDefinitionLabel(
            Node definition,
            String source,
            char[] sanitized) {
        SourceBounds bounds = sourceBounds(definition, source.length());
        if (bounds == null) {
            return;
        }
        int opening = source.indexOf('[', bounds.start());
        if (opening < 0 || opening >= bounds.end()) {
            return;
        }
        int closing = findReferenceLabelEnd(source, opening + 1, bounds.end());
        if (closing >= 0) {
            mask(sanitized, opening, closing + 1);
        }
    }

    private static int findReferenceLabelEnd(String source, int start, int limit) {
        boolean escaped = false;
        for (int index = start; index < limit; index++) {
            char character = source.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == ']') {
                return index;
            }
        }
        return -1;
    }

    private static void maskCheckboxes(char[] content) {
        for (int index = 0; index + 2 < content.length; index++) {
            if (content[index] == '['
                    && content[index + 2] == ']'
                    && (content[index + 1] == ' '
                    || content[index + 1] == 'x'
                    || content[index + 1] == 'X')) {
                mask(content, index, index + 3);
                index += 2;
            }
        }
    }

    private static void maskSourceSpans(Node node, char[] sanitized) {
        for (SourceSpan span : node.getSourceSpans()) {
            if (span.getInputIndex() >= 0 && span.getLength() >= 0) {
                mask(
                        sanitized,
                        span.getInputIndex(),
                        span.getInputIndex() + span.getLength());
            }
        }
    }

    private static SourceBounds sourceBounds(Node node, int sourceLength) {
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (SourceSpan span : node.getSourceSpans()) {
            if (span.getInputIndex() < 0 || span.getLength() < 0) {
                return null;
            }
            start = Math.min(start, span.getInputIndex());
            end = Math.max(end, span.getInputIndex() + span.getLength());
        }
        if (start == Integer.MAX_VALUE || start > end || end > sourceLength) {
            return null;
        }
        return new SourceBounds(start, end);
    }

    private static void mask(char[] content, int start, int end) {
        for (int index = Math.max(0, start); index < Math.min(end, content.length); index++) {
            if (content[index] != '\n' && content[index] != '\r') {
                content[index] = ' ';
            }
        }
    }

    private static String normalizeTypography(String value) {
        String normalized = normalizeTypographyPreservingLines(value);
        StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean previousWhitespace = false;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean whitespace = Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint);
            if (whitespace) {
                if (!previousWhitespace) {
                    collapsed.append(' ');
                }
            } else {
                collapsed.appendCodePoint(codePoint);
            }
            previousWhitespace = whitespace;
        }
        return collapsed.toString();
    }

    private static String normalizeTypographyPreservingLines(String value) {
        String compatibility = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder normalized = new StringBuilder(compatibility.length());
        boolean previousHorizontalWhitespace = false;
        for (int index = 0; index < compatibility.length(); ) {
            int codePoint = compatibility.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT) {
                continue;
            }
            if (codePoint == '\r' || codePoint == '\n') {
                normalized.appendCodePoint(codePoint);
                previousHorizontalWhitespace = false;
                continue;
            }
            boolean horizontalWhitespace = Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint);
            if (horizontalWhitespace) {
                if (!previousHorizontalWhitespace) {
                    normalized.append(' ');
                }
            } else {
                normalized.appendCodePoint(Character.toUpperCase(codePoint));
            }
            previousHorizontalWhitespace = horizontalWhitespace;
        }
        return normalized.toString().toUpperCase(Locale.ROOT);
    }

    private record SourceBounds(int start, int end) {
    }

    private record VisibleText(String sourceLines, String softBreaksAsSpaces) {
    }

    /** Contenido editorialmente limpio, preservado sin reescritura. */
    public record CleanLegalText(String content) {

        public CleanLegalText {
            Objects.requireNonNull(content, "content");
        }
    }
}
