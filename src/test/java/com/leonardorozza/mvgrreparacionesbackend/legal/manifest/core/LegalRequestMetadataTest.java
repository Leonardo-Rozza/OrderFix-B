package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata.parseIpLiteral;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalRequestMetadataTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0.0.0.0|0.0.0.0",
            "127.0.0.1|127.0.0.1",
            "192.0.2.255|192.0.2.255",
            "255.255.255.255|255.255.255.255",
            "::|::",
            "::1|::1",
            "2001:DB8:0000:0000:0000:0000:0000:0001|2001:db8::1",
            "2001:db8:0:0:1:0:0:1|2001:db8::1:0:0:1",
            "2001:db8:0:1:0:0:0:1|2001:db8:0:1::1",
            "1:0:2:3:4:5:6:7|1:0:2:3:4:5:6:7",
            "1:2:3:4:5:6:0:0|1:2:3:4:5:6::",
            "0:0:0:0:0:0:0:0|::",
            "0001:0002:0003:0004:0005:0006:0007:0008|1:2:3:4:5:6:7:8",
            "1:2:3:4:5:6:192.0.2.1|1:2:3:4:5:6:c000:201",
            "::192.0.2.1|::c000:201",
            "2001:db8::192.0.2.1|2001:db8::c000:201",
            "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255|ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "::ffff:192.0.2.1|192.0.2.1",
            "::FFFF:c000:0201|192.0.2.1",
            "0:0:0:0:0:FFFF:C000:0201|192.0.2.1",
            "::ffff:0.0.0.0|0.0.0.0"
    })
    void parsesStrictLiteralsAndCanonicalizesWithoutChangingTheAddress(String literal, String expected) {
        var address = parseIpLiteral(literal);

        assertThat(address.canonical()).isEqualTo(expected);
        assertThat(parseIpLiteral(address.canonical())).isEqualTo(address).hasSameHashCodeAs(address);
        assertThat(address.isIpv4()).isEqualTo(expected.indexOf(':') < 0);
        assertThat(address.addressBytes()).hasSize(address.isIpv4() ? 4 : 16);
        assertThat(LegalRequestMetadata.of(address, null).ipAddress()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "localhost", "workshop.example.invalid", "127.1", "127.0.1", "2130706433", "0x7f000001",
            "0177.0.0.1", "127.00.0.1", "1.2.3.004", "256.1.2.3", "1.-2.3.4", "1.+2.3.4", "1.a.3.4",
            ".1.2.3", "1.2.3.", "1..2.3", "1.2.3.4.5", "1.2.3.4/32", "1.2.3.4:80",
            " 192.0.2.1", "192.0.2.1 ", "192.0.2.1\n", "192.0.2.1\u0000", "１９２.0.2.1",
            ":", ":1", "1:", ":::1", "1:::2", "1::2::3", ":::" , "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::8",
            "12345::", "gggg::", "[::1]", "[::1]:443", "fe80::1%eth0", "fe80::1%1", "::1/128",
            ":: 1", "::\t1", "::\u00851", "::\ud800", "1.2.3.4::", "1.2.3.4::1",
            "::1.2.3.4:5", "1:2:3:4:5:192.0.2.1", "1:2:3:4:5:6:7:192.0.2.1", "::ffff:192.000.2.1"
    })
    void rejectsAmbiguousHostOrMalformedInputsWithSafeDiagnostics(String literal) {
        assertThatThrownBy(() -> parseIpLiteral(literal))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata legal inválida").hasNoCause();
    }

    @Test void inputLengthIsBoundedBeforeParsing() {
        assertThatThrownBy(() -> parseIpLiteral("1".repeat(1_048_576)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata legal inválida").hasNoCause();
    }

    @Test void mappedAndIpv4ValuesShareEqualityAndNeitherLeaksItsBytes() {
        var address = parseIpLiteral("::ffff:192.0.2.1");
        byte[] first = address.addressBytes();
        assertThat(first).containsExactly((byte) 192, (byte) 0, (byte) 2, (byte) 1);
        first[0] = 10;

        assertThat(address.addressBytes()).containsExactly((byte) 192, (byte) 0, (byte) 2, (byte) 1);
        assertThat(address.addressBytes()).isNotSameAs(address.addressBytes());
        assertThat(address.canonical()).isEqualTo("192.0.2.1");
        assertThat(address).isEqualTo(parseIpLiteral("192.0.2.1"))
                .isNotEqualTo(parseIpLiteral("::192.0.2.1")).isNotEqualTo(null).isNotEqualTo("192.0.2.1");
    }

    @Test void metadataAndAddressConstructionCannotBypassValidation() {
        for (Class<?> type : new Class<?>[] {LegalRequestMetadata.class, LegalRequestMetadata.IpAddress.class}) {
            assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
            assertThat(type.getConstructors()).isEmpty();
            for (var constructor : type.getDeclaredConstructors()) {
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            }
            for (var field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers())).as(field.getName()).isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
                }
            }
        }
        assertThatThrownBy(() -> LegalRequestMetadata.of(null, "private-client"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata legal inválida").hasNoCause();
    }

    @Test void absentAndEmptyUserAgentsAreAbsentButOtherValuesArePreservedExactly() {
        var ip = parseIpLiteral("192.0.2.1");
        assertThat(LegalRequestMetadata.of(ip, null).userAgent()).isNull();
        assertThat(LegalRequestMetadata.of(ip, "").userAgent()).isNull();
        String exact = "  Client/1.0 e\u0301 \ud83d\udd27 \u200d\uffff  ";
        assertThat(LegalRequestMetadata.of(ip, exact).userAgent()).isSameAs(exact);
        assertThat(LegalRequestMetadata.of(ip, " ").userAgent()).isEqualTo(" ");
    }

    @Test void userAgentLimitCountsScalarsAndAllowsAtMost2048Utf8Bytes() {
        var ip = parseIpLiteral("192.0.2.1");
        String ascii = "a".repeat(512);
        String supplementary = "\ud83d\udd27".repeat(512);
        assertThat(LegalRequestMetadata.of(ip, ascii).userAgent()).isEqualTo(ascii);
        assertThat(LegalRequestMetadata.of(ip, supplementary).userAgent()).isEqualTo(supplementary);
        assertThat(supplementary).hasSize(1024);
        assertThat(supplementary.getBytes(StandardCharsets.UTF_8)).hasSize(2048);
        for (String excessive : new String[] {ascii + "a", supplementary + "a", "a".repeat(1025)}) {
            assertThatThrownBy(() -> LegalRequestMetadata.of(ip, excessive))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Metadata legal inválida").hasNoCause();
        }
    }

    @Test void everyC0C1ControlIsRejectedAnywhereInAUserAgent() {
        var ip = parseIpLiteral("192.0.2.1");
        for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
            if (codePoint >= 0x20 && codePoint < 0x7f) continue;
            String invalid = "private-agent" + (char) codePoint + "suffix";
            assertThatThrownBy(() -> LegalRequestMetadata.of(ip, invalid))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Metadata legal inválida").hasNoCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\ud800", "\udc00", "private\ud800agent", "\ud800\ud800", "\udc00\ud800", "\ud800x\udc00"})
    void malformedSurrogatesCannotBeSilentlyReplacedDuringEncryption(String userAgent) {
        assertThatThrownBy(() -> LegalRequestMetadata.of(parseIpLiteral("192.0.2.1"), userAgent))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata legal inválida").hasNoCause();
    }

    @Test void diagnosticsRedactBothMetadataFields() {
        var ip = parseIpLiteral("192.0.2.1");
        var metadata = LegalRequestMetadata.of(ip, "private-client-agent");
        assertThat(ip.toString()).isEqualTo("IpAddress[redacted]");
        assertThat(metadata.toString()).isEqualTo("LegalRequestMetadata[redacted]");
        assertThat(metadata.toString() + ip).doesNotContain("192.0.2.1", "private-client-agent");
    }
}
