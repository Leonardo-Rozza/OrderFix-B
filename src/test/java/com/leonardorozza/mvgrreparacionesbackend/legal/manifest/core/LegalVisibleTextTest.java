package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LegalVisibleTextTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " \t\n",
            "\u00A0\u2007\u202F",
            "\u200B\u2060\uFEFF",
            "\u200E\u200F",
            "\uFE0F\u034F\u0301\u20DD"
    })
    void rejectsTextMadeOnlyOfSpacingOrFormatCharacters(String value) {
        assertThat(LegalVisibleText.hasVisibleContent(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OrdenFix",
            " \u200Btexto\u2060 ",
            ".",
            "😀"
    })
    void acceptsAtLeastOneVisibleCodePoint(String value) {
        assertThat(LegalVisibleText.hasVisibleContent(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OrdenFix",
            " \u200Btexto\u2060 ",
            ".",
            "😀"
    })
    void acceptsPublishableTextWithoutControls(String value) {
        assertThat(LegalVisibleText.isPublishable(value)).isTrue();
    }

    @Test
    void rejectsControlsEvenWhenOtherVisibleContentExists() {
        String nul = Character.toString(0);

        assertThat(LegalVisibleText.isPublishable(nul)).isFalse();
        assertThat(LegalVisibleText.isPublishable("Orden" + nul + "Fix")).isFalse();
    }
}
