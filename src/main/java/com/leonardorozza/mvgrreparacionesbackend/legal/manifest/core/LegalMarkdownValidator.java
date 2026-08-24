package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida las reglas Markdown publicables del contrato legal v1 y deriva su título persistible.
 *
 * <p>CommonMark decide qué construcciones son semánticamente Markdown. Sobre ese AST, el contrato
 * exige un H1 ATX de texto plano, bloquea HTML real y rechaza destinos con esquemas activos. El
 * código inline o fenced permanece inerte y nunca se interpreta como HTML o enlace.</p>
 */
public final class LegalMarkdownValidator {

    static final int MAX_TITLE_CODE_POINTS = 300;
    static final int MAX_COMMONMARK_BLOCK_PARSERS = 100;
    static final int MAX_COMMONMARK_INLINE_NESTING = 100;

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .maxOpenBlockParsers(MAX_COMMONMARK_BLOCK_PARSERS)
            .maxInlineNesting(MAX_COMMONMARK_INLINE_NESTING)
            .build();
    private static final Pattern ATX_H1_SOURCE = Pattern.compile(
            "^[ ]{0,3}#(?:[ \\t]+(.*))?$");
    private static final Pattern LINK_ENTITY = Pattern.compile(
            "&#[xX]([0-9A-Fa-f]+);|&#([0-9]+);|&(colon|tab|newline);",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Valida el Markdown y devuelve exclusivamente el título derivado cuando pasa.
     */
    public LegalManifestValidation<ValidatedMarkdown> validate(String markdown, String location) {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(location, "location");

        // Construir un issue valida la location también en el camino PASS sin exponer contenido.
        LegalManifestIssue h1Required = issue(
                LegalManifestIssueCode.DOCUMENT_H1_REQUIRED,
                location);
        MarkdownInspection inspection = inspect(PARSER.parse(markdown), markdown);
        List<LegalManifestIssue> issues = new ArrayList<>();

        AtxHeading candidate = inspection.firstAtxH1;
        String title = null;
        if (candidate == null || candidate.title().isBlank()) {
            issues.add(h1Required);
        } else {
            title = candidate.title().strip();
            if (!candidate.plainText()) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_H1_PLAIN_TEXT_REQUIRED,
                        location));
            }
            if (title.codePointCount(0, title.length()) > MAX_TITLE_CODE_POINTS) {
                issues.add(issue(
                        LegalManifestIssueCode.DOCUMENT_H1_TOO_LONG,
                        location));
            }
        }

        if (inspection.htmlForbidden) {
            issues.add(issue(LegalManifestIssueCode.DOCUMENT_HTML_FORBIDDEN, location));
        }
        if (inspection.activeLinkForbidden) {
            issues.add(issue(
                    LegalManifestIssueCode.DOCUMENT_ACTIVE_LINK_FORBIDDEN,
                    location));
        }

        if (!issues.isEmpty()) {
            return LegalManifestValidation.failure(issues);
        }
        return LegalManifestValidation.pass(new ValidatedMarkdown(title));
    }

    private static MarkdownInspection inspect(Node document, String source) {
        MarkdownInspection inspection = new MarkdownInspection();
        inspectNode(document, source, inspection);
        return inspection;
    }

    private static void inspectNode(
            Node node,
            String source,
            MarkdownInspection inspection) {
        if (node instanceof Heading heading
                && heading.getLevel() == 1
                && inspection.firstAtxH1 == null) {
            AtxHeading candidate = atxHeading(heading, source);
            if (candidate != null) {
                inspection.firstAtxH1 = candidate;
            }
        } else if (node instanceof HtmlInline || node instanceof HtmlBlock) {
            inspection.htmlForbidden = true;
        } else if (node instanceof Link link) {
            if (isActiveDestination(link.getDestination())) {
                inspection.activeLinkForbidden = true;
            }
            if (isAngleAutolink(link, source) && !isAllowedAutolink(link.getDestination())) {
                inspection.htmlForbidden = true;
            }
        } else if (node instanceof Image image) {
            if (isActiveDestination(image.getDestination())) {
                inspection.activeLinkForbidden = true;
            }
        } else if (node instanceof LinkReferenceDefinition definition
                && isActiveDestination(definition.getDestination())) {
            inspection.activeLinkForbidden = true;
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            inspectNode(child, source, inspection);
        }
    }

    private static AtxHeading atxHeading(Heading heading, String source) {
        SourceBounds bounds = sourceBounds(heading, source.length());
        if (bounds == null) {
            return null;
        }
        String headingSource = source.substring(bounds.start(), bounds.end());
        Matcher matcher = ATX_H1_SOURCE.matcher(headingSource);
        if (!matcher.matches()) {
            return null;
        }

        StringBuilder renderedTitle = new StringBuilder();
        boolean onlyTextChildren = true;
        for (Node child = heading.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                renderedTitle.append(text.getLiteral());
            } else {
                onlyTextChildren = false;
                appendVisibleText(child, renderedTitle);
            }
        }

        String title = renderedTitle.toString().strip();
        String sourceTitle = Objects.requireNonNullElse(matcher.group(1), "").strip();
        boolean sourceDerivesWithoutMarkup = sourceTitle.equals(title)
                && !sourceTitle.contains("~~");
        return new AtxHeading(title, onlyTextChildren && sourceDerivesWithoutMarkup);
    }

    private static void appendVisibleText(Node node, StringBuilder target) {
        if (node instanceof Text text) {
            target.append(text.getLiteral());
            return;
        }
        if (node instanceof Code code) {
            target.append(code.getLiteral());
            return;
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            target.append(' ');
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendVisibleText(child, target);
        }
    }

    private static boolean isAngleAutolink(Link link, String source) {
        SourceBounds bounds = sourceBounds(link, source.length());
        if (bounds == null) {
            return false;
        }
        String raw = source.substring(bounds.start(), bounds.end()).strip();
        return raw.startsWith("<") && raw.endsWith(">");
    }

    private static boolean isAllowedAutolink(String destination) {
        String normalized = Objects.requireNonNullElse(destination, "")
                .toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://") || normalized.startsWith("mailto:");
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

    private static boolean isActiveDestination(String rawDestination) {
        String decoded = decodeMarkdownBackslashEscapes(
                decodeLinkEntities(Objects.requireNonNullElse(rawDestination, "")));
        StringBuilder compact = new StringBuilder(decoded.length());
        decoded.codePoints()
                .filter(codePoint -> codePoint > 0x20 && codePoint != 0x7F)
                .forEach(compact::appendCodePoint);

        String normalized = compact.toString();
        if (normalized.startsWith("<")) {
            normalized = normalized.substring(1);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("javascript:")
                || lower.startsWith("vbscript:")
                || lower.startsWith("data:");
    }

    private static String decodeMarkdownBackslashEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\'
                    && index + 1 < value.length()
                    && isAsciiPunctuation(value.charAt(index + 1))) {
                decoded.append(value.charAt(++index));
            } else {
                decoded.append(character);
            }
        }
        return decoded.toString();
    }

    private static boolean isAsciiPunctuation(char character) {
        return character >= '!' && character <= '/'
                || character >= ':' && character <= '@'
                || character >= '[' && character <= '`'
                || character >= '{' && character <= '~';
    }

    private static String decodeLinkEntities(String value) {
        Matcher matcher = LINK_ENTITY.matcher(value);
        StringBuffer decoded = new StringBuffer(value.length());
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = decodeCodePoint(matcher.group(1), 16, matcher.group());
            } else if (matcher.group(2) != null) {
                replacement = decodeCodePoint(matcher.group(2), 10, matcher.group());
            } else {
                replacement = switch (matcher.group(3).toLowerCase(Locale.ROOT)) {
                    case "colon" -> ":";
                    case "tab" -> "\t";
                    case "newline" -> "\n";
                    default -> matcher.group();
                };
            }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    private static String decodeCodePoint(
            String encoded,
            int radix,
            String fallback) {
        try {
            int codePoint = Integer.parseInt(encoded, radix);
            if (!Character.isValidCodePoint(codePoint)
                    || codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE) {
                return fallback;
            }
            return new String(Character.toChars(codePoint));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static LegalManifestIssue issue(
            LegalManifestIssueCode code,
            String location) {
        return LegalManifestIssue.at(code, location);
    }

    private static final class MarkdownInspection {

        private AtxHeading firstAtxH1;
        private boolean htmlForbidden;
        private boolean activeLinkForbidden;
    }

    private record AtxHeading(String title, boolean plainText) {
    }

    private record SourceBounds(int start, int end) {
    }

    /** Resultado seguro del Markdown; no conserva una representación renderizada. */
    public record ValidatedMarkdown(String title) {

        public ValidatedMarkdown {
            Objects.requireNonNull(title, "title");
        }
    }
}
