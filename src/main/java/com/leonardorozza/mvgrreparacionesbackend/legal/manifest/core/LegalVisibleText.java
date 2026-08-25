package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.Objects;

/** Shared policy for fields that must contain publishable, genuinely visible text. */
final class LegalVisibleText {

    private LegalVisibleText() {
    }

    static boolean hasVisibleContent(String value) {
        Objects.requireNonNull(value, "value");
        return value.codePoints().anyMatch(LegalVisibleText::isVisible);
    }

    static boolean isPublishable(String value) {
        Objects.requireNonNull(value, "value");
        return hasVisibleContent(value)
                && value.codePoints().noneMatch(LegalVisibleText::isControl);
    }

    private static boolean isVisible(int codePoint) {
        return !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)
                && !isInvisibleType(Character.getType(codePoint));
    }

    private static boolean isInvisibleType(int type) {
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL;
    }
}
