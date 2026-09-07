package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata.IpAddress;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Resolves metadata from the original socket peer and an explicitly trusted proxy chain.
 * The container must not replace remoteAddr with an unverified forwarded address.
 */
public final class LegalRequestMetadataResolver {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_TRUSTED_CIDRS = 64;
    private static final int MAX_CIDR_LENGTH = 49;
    private static final int MAX_FORWARDED_LENGTH = 4096;
    private static final int MAX_FORWARDED_HOPS = 32;
    private final List<TrustedCidr> trustedProxies;

    /** No default trust: an empty list uses only the socket peer. CIDRs must name network bases. */
    public LegalRequestMetadataResolver(List<String> trustedProxyCidrs) {
        if (trustedProxyCidrs == null || trustedProxyCidrs.size() > MAX_TRUSTED_CIDRS) {
            throw invalidConfiguration();
        }
        List<TrustedCidr> proxies = new ArrayList<>(trustedProxyCidrs.size());
        for (String cidr : trustedProxyCidrs) proxies.add(parseCidr(cidr));
        this.trustedProxies = List.copyOf(proxies);
    }

    public LegalRequestMetadata resolve(HttpServletRequest request) {
        if (request == null) throw invalid();
        IpAddress address = LegalRequestMetadata.parseIpLiteral(request.getRemoteAddr());
        if (isTrusted(address)) {
            List<IpAddress> forwarded = parseForwarded(singleHeader(request, FORWARDED_FOR, true));
            for (int i = forwarded.size() - 1; i >= 0 && isTrusted(address); i--) {
                address = forwarded.get(i);
            }
        }
        return LegalRequestMetadata.of(address, singleHeader(request, "User-Agent", false));
    }

    @Override public String toString() { return "LegalRequestMetadataResolver[redacted]"; }

    private boolean isTrusted(IpAddress address) {
        byte[] bytes = address.addressBytes();
        for (TrustedCidr proxy : trustedProxies) if (proxy.contains(bytes)) return true;
        return false;
    }

    private static String singleHeader(HttpServletRequest request, String name, boolean required) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            if (required) throw invalid();
            return null;
        }
        String value = values.nextElement();
        if (value == null || values.hasMoreElements()) throw invalid();
        return value;
    }

    private static List<IpAddress> parseForwarded(String header) {
        if (header.isEmpty() || header.length() > MAX_FORWARDED_LENGTH) throw invalid();
        int hops = 1;
        for (int i = 0; i < header.length(); i++) {
            char character = header.charAt(i);
            if ((character < 32 && character != '\t') || character > 126) throw invalid();
            if (character == ',' && ++hops > MAX_FORWARDED_HOPS) throw invalid();
        }
        String[] tokens = header.split(",", -1);
        List<IpAddress> result = new ArrayList<>(tokens.length);
        // Validate the complete bounded chain before selecting its trusted suffix.
        for (String token : tokens) {
            int start = 0;
            int end = token.length();
            while (start < end && isOptionalWhitespace(token.charAt(start))) start++;
            while (end > start && isOptionalWhitespace(token.charAt(end - 1))) end--;
            result.add(LegalRequestMetadata.parseIpLiteral(token.substring(start, end)));
        }
        return List.copyOf(result);
    }

    private static boolean isOptionalWhitespace(char character) { return character == ' ' || character == '\t'; }

    private static TrustedCidr parseCidr(String cidr) {
        if (cidr == null || cidr.isEmpty() || cidr.length() > MAX_CIDR_LENGTH) throw invalidConfiguration();
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw invalidConfiguration();
        }
        String prefixText = cidr.substring(slash + 1);
        if (prefixText.length() > 3 || (prefixText.length() > 1 && prefixText.charAt(0) == '0')) {
            throw invalidConfiguration();
        }
        int prefix = 0;
        for (int i = 0; i < prefixText.length(); i++) {
            char character = prefixText.charAt(i);
            if (character < '0' || character > '9') throw invalidConfiguration();
            prefix = prefix * 10 + character - '0';
        }
        String literal = cidr.substring(0, slash);
        IpAddress network;
        try {
            network = LegalRequestMetadata.parseIpLiteral(literal);
        } catch (IllegalArgumentException ignored) {
            throw invalidConfiguration();
        }
        if (network.isIpv4() && literal.indexOf(':') >= 0) {
            // ::ffff:0:0/96 and more specific mapped networks share the IPv4 trust domain.
            if (prefix < 96 || prefix > 128) throw invalidConfiguration();
            prefix -= 96;
        } else if (prefix > (network.isIpv4() ? 32 : 128)) {
            throw invalidConfiguration();
        }
        byte[] bytes = network.addressBytes();
        for (int bit = prefix; bit < bytes.length * 8; bit++) {
            if ((bytes[bit / 8] & (1 << (7 - bit % 8))) != 0) throw invalidConfiguration();
        }
        return new TrustedCidr(bytes, prefix);
    }

    private static final class TrustedCidr {
        private final byte[] network;
        private final int prefix;

        private TrustedCidr(byte[] network, int prefix) {
            this.network = network.clone();
            this.prefix = prefix;
        }

        private boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
            }
            return true;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Metadata legal inválida");
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Configuración de proxies legales inválida");
    }
}
