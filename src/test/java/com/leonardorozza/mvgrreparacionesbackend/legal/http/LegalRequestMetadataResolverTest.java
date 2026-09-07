package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalRequestMetadataResolverTest {
    private static final String XFF = "X-Forwarded-For";
    private static final String UA = "User-Agent";

    @Test void emptyConfigurationUsesOnlyTheSocketPeerWithoutReadingForwardingHeaders() {
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.123");
        when(request.getHeaders(UA)).thenReturn(Collections.enumeration(List.of("private-client")));
        var metadata = new LegalRequestMetadataResolver(List.of()).resolve(request);

        assertThat(metadata.ipAddress()).isEqualTo("192.0.2.123");
        assertThat(metadata.userAgent()).isEqualTo("private-client");
        verify(request, never()).getHeaders(XFF);
        verify(request, never()).getHeader(XFF);
        verify(request, never()).getHeaders("Forwarded");
        verify(request, never()).getHeaders("X-Real-IP");
    }

    @Test void anUntrustedPeerCannotSelectAnAddressEvenWithMalformedDuplicateOrOversizedHeaders() {
        var resolver = new LegalRequestMetadataResolver(List.of("10.0.0.0/8"));
        var request = request("192.0.2.123");
        request.addHeader(XFF, "forged.invalid\r\n");
        request.addHeader(XFF, "x".repeat(100_000));
        request.addHeader("Forwarded", "for=10.0.0.1");
        request.addHeader("X-Real-IP", "10.0.0.1");

        assertThat(resolver.resolve(request).ipAddress()).isEqualTo("192.0.2.123");
    }

    @Test void chainIsTraversedFromTheSocketPeerUntilTheFirstUntrustedHop() {
        var resolver = new LegalRequestMetadataResolver(List.of("10.0.0.0/8", "2001:db8:10::/48"));
        var request = request("10.1.2.3");
        request.addHeader(XFF, "198.51.100.99, 192.0.2.123,\t2001:DB8:10::1, 10.2.3.4\t");

        assertThat(resolver.resolve(request).ipAddress()).isEqualTo("192.0.2.123");
    }

    @Test void trustedSingleHopAndAChainOfOnlyTrustedHopsUseTheLeftmostAddress() {
        var resolver = new LegalRequestMetadataResolver(List.of("10.0.0.0/8"));
        var direct = request("10.1.2.3");
        direct.addHeader(XFF, " 192.0.2.123 ");
        assertThat(resolver.resolve(direct).ipAddress()).isEqualTo("192.0.2.123");

        var trusted = request("10.1.2.3");
        trusted.addHeader(XFF, "10.3.4.5, 10.2.3.4");
        assertThat(resolver.resolve(trusted).ipAddress()).isEqualTo("10.3.4.5");
    }

    @Test void mappedPeersAndCidrsShareTheSameExplicitIpv4TrustDomain() {
        var ipv4 = new LegalRequestMetadataResolver(List.of("192.0.2.0/24"));
        var mapped = new LegalRequestMetadataResolver(List.of("::ffff:192.0.2.0/120"));
        for (String peer : List.of("192.0.2.12", "::ffff:192.0.2.12", "::FFFF:c000:20c")) {
            var request = request(peer);
            request.addHeader(XFF, "::ffff:198.51.100.8");
            assertThat(ipv4.resolve(request).ipAddress()).isEqualTo("198.51.100.8");
            assertThat(mapped.resolve(request).ipAddress()).isEqualTo("198.51.100.8");
        }
        var outside = request("192.0.3.12");
        assertThat(mapped.resolve(outside).ipAddress()).isEqualTo("192.0.3.12");

        var compatible = request("::192.0.2.12");
        assertThat(mapped.resolve(compatible).ipAddress()).isEqualTo("::c000:20c");
    }

    @Test void cidrMasksMatchBitsAndFamiliesIncludingSingletonsAndExplicitUniversalNetworks() {
        var ipv6 = new LegalRequestMetadataResolver(List.of("2001:db8::/33"));
        var inside = request("2001:db8:7fff:ffff::1");
        inside.addHeader(XFF, "2001:db8:8000::1");
        assertThat(ipv6.resolve(inside).ipAddress()).isEqualTo("2001:db8:8000::1");
        assertThat(ipv6.resolve(request("2001:db8:8000::2")).ipAddress()).isEqualTo("2001:db8:8000::2");
        assertThat(ipv6.resolve(request("192.0.2.1")).ipAddress()).isEqualTo("192.0.2.1");

        var singleton = new LegalRequestMetadataResolver(List.of("192.0.2.1/32", "2001:db8::1/128"));
        for (String peer : List.of("192.0.2.1", "2001:db8::1")) {
            var request = request(peer);
            request.addHeader(XFF, "198.51.100.1");
            assertThat(singleton.resolve(request).ipAddress()).isEqualTo("198.51.100.1");
        }
        assertThat(singleton.resolve(request("192.0.2.2")).ipAddress()).isEqualTo("192.0.2.2");

        for (String cidr : List.of("0.0.0.0/0", "::ffff:0.0.0.0/96")) {
            var request = request("192.0.2.1");
            request.addHeader(XFF, "198.51.100.1");
            assertThat(new LegalRequestMetadataResolver(List.of(cidr)).resolve(request).ipAddress())
                    .isEqualTo("198.51.100.1");
        }
        var universalV6 = new LegalRequestMetadataResolver(List.of("::/0"));
        var request = request("2001:db8::1");
        request.addHeader(XFF, "2001:db8::2");
        assertThat(universalV6.resolve(request).ipAddress()).isEqualTo("2001:db8::2");
        assertThat(universalV6.resolve(request("::ffff:192.0.2.1")).ipAddress()).isEqualTo("192.0.2.1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"private-peer.example.invalid", "127.1", "192.0.2.01", "[::1]", "fe80::1%en0", "192.0.2.1\r"})
    void invalidSocketPeerFailsBeforeAnOtherwiseValidForwardedAddress(String peer) {
        var request = request(peer);
        request.addHeader(XFF, "192.0.2.123");
        assertInvalid(() -> new LegalRequestMetadataResolver(List.of("0.0.0.0/0")).resolve(request));
    }

    @Test void missingRequestFailsClosed() {
        assertInvalid(() -> new LegalRequestMetadataResolver(List.of()).resolve(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t", "192.0.2.1,", ",192.0.2.1", "192.0.2.1,,10.0.0.1", "unknown",
            "for=192.0.2.1", "\"192.0.2.1\"", "192.0.2.1:443", "[2001:db8::1]", "fe80::1%eth0",
            "private-host.invalid", "192.0.2.1\r\n", "192.0.2.1\u0000", "192.0.2.1\u007f", "192.0.2.1\u0085",
            "192.0.2.1\u00a0", "192.0.\t2.1", "bad-prefix.invalid,192.0.2.123,10.0.0.1"
    })
    void aTrustedPeerRequiresACompleteValidBoundedForwardedChain(String forwarded) {
        var request = request("10.0.0.2");
        if (forwarded != null) request.addHeader(XFF, forwarded);
        assertInvalid(() -> new LegalRequestMetadataResolver(List.of("10.0.0.0/8")).resolve(request));
    }

    @Test void duplicateForwardedHeadersAreRejectedEvenIfTheyAreIndividuallyValidOrEqual() {
        var request = request("10.0.0.2");
        request.addHeader(XFF, "192.0.2.1");
        request.addHeader(XFF, "192.0.2.1");
        assertInvalid(() -> new LegalRequestMetadataResolver(List.of("10.0.0.0/8")).resolve(request));
    }

    @Test void forwardedHopAndCharacterLimitsAreInclusiveAndCheckedBeforeSplitting() {
        var resolver = new LegalRequestMetadataResolver(List.of("10.0.0.0/8"));
        String thirtyTwo = String.join(",", Collections.nCopies(32, "10.0.0.1"));
        var maximumHops = request("10.0.0.2");
        maximumHops.addHeader(XFF, thirtyTwo);
        assertThat(resolver.resolve(maximumHops).ipAddress()).isEqualTo("10.0.0.1");

        var tooMany = request("10.0.0.2");
        tooMany.addHeader(XFF, thirtyTwo + ",10.0.0.1");
        assertInvalid(() -> resolver.resolve(tooMany));

        String maximum = " ".repeat(4096 - "192.0.2.1".length()) + "192.0.2.1";
        var maximumCharacters = request("10.0.0.2");
        maximumCharacters.addHeader(XFF, maximum);
        assertThat(resolver.resolve(maximumCharacters).ipAddress()).isEqualTo("192.0.2.1");

        var tooLong = request("10.0.0.2");
        tooLong.addHeader(XFF, maximum + " ");
        assertInvalid(() -> resolver.resolve(tooLong));
    }

    @Test void optionalUserAgentIsPreservedAndValidatedAsScalars() {
        var resolver = new LegalRequestMetadataResolver(List.of());
        assertThat(resolver.resolve(request("192.0.2.1")).userAgent()).isNull();
        var empty = request("192.0.2.1");
        empty.addHeader(UA, "");
        assertThat(resolver.resolve(empty).userAgent()).isNull();
        String exact = " PrivateClient/e\u0301 \ud83d\udd27 ";
        var request = request("192.0.2.1");
        request.addHeader(UA, exact);
        assertThat(resolver.resolve(request).userAgent()).isEqualTo(exact);
        var maximum = request("192.0.2.1");
        maximum.addHeader(UA, "\ud83d\udd27".repeat(512));
        assertThat(resolver.resolve(maximum).userAgent()).isEqualTo("\ud83d\udd27".repeat(512));
    }

    @Test void excessiveMalformedOrDuplicateUserAgentsFailClosedIncludingAnEmptyFirstValue() {
        var resolver = new LegalRequestMetadataResolver(List.of());
        for (String userAgent : List.of("a".repeat(513), "\ud83d\udd27".repeat(513), "private\r\nagent",
                "private\u0085agent", "private\ud800agent", "private\tclient")) {
            var request = request("192.0.2.1");
            request.addHeader(UA, userAgent);
            assertInvalid(() -> resolver.resolve(request));
        }
        for (String first : List.of("", "private-client")) {
            var request = request("192.0.2.1");
            request.addHeader(UA, first);
            request.addHeader(UA, "private-client");
            assertInvalid(() -> resolver.resolve(request));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "192.0.2.1", "workshop.internal/32", "192.0.2.1/24", "2001:db8::1/64", "192.0.2.0/33", "::/129",
            "192.0.2.0/-1", "192.0.2.0/+24", "192.0.2.0/024", "192.0.2.0/", "/24", "192.0.2.0/24/24",
            "192.0.2.0/24 ", " 192.0.2.0/24", "192.0.2.0/2a", "192.0.2.0/٢٤", "::ffff:0.0.0.0/95",
            "::ffff:192.0.2.0/24", "::ffff:192.0.2.1/120", "[2001:db8::]/32", "fe80::%eth0/64",
            "192.0.2.0/2147483648", "192.0.2.0/24\n", "private-proxy.internal/24"
    })
    void malformedOrAmbiguousProxyConfigurationFailsWithSafeDiagnostics(String cidr) {
        assertThatThrownBy(() -> new LegalRequestMetadataResolver(Collections.singletonList(cidr)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Configuración de proxies legales inválida").hasNoCause();
    }

    @Test void cidrConfigurationHasABoundedCopiedListWithoutImplicitTrust() {
        assertThatThrownBy(() -> new LegalRequestMetadataResolver(null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Configuración de proxies legales inválida").hasNoCause();
        assertThatThrownBy(() -> new LegalRequestMetadataResolver(Collections.nCopies(65, "10.0.0.0/8")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Configuración de proxies legales inválida").hasNoCause();
        assertThatThrownBy(() -> new LegalRequestMetadataResolver(List.of("x".repeat(100_000))))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Configuración de proxies legales inválida").hasNoCause();
        var maximum = new LegalRequestMetadataResolver(Collections.nCopies(64, "10.0.0.0/8"));
        assertThat(maximum.resolve(request("192.0.2.1")).ipAddress()).isEqualTo("192.0.2.1");
        var longest = new LegalRequestMetadataResolver(List.of("ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255/128"));
        assertThat(longest.resolve(request("192.0.2.1")).ipAddress()).isEqualTo("192.0.2.1");

        List<String> source = new ArrayList<>(List.of("10.0.0.0/8"));
        var copied = new LegalRequestMetadataResolver(source);
        source.clear();
        source.add("0.0.0.0/0");
        var trusted = request("10.0.0.1");
        trusted.addHeader(XFF, "192.0.2.1");
        assertThat(copied.resolve(trusted).ipAddress()).isEqualTo("192.0.2.1");
        assertThat(copied.resolve(request("198.51.100.1")).ipAddress()).isEqualTo("198.51.100.1");
        assertThat(copied.toString()).isEqualTo("LegalRequestMetadataResolver[redacted]");
    }

    private static MockHttpServletRequest request(String peer) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        return request;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metadata legal inválida").hasNoCause();
    }
}
