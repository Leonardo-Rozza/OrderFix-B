package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import java.util.Arrays;

/**
 * Validated metadata shape shared by transport and encryption. Construction does not
 * authenticate a caller or establish that an address came from a trusted peer.
 */
public final class LegalRequestMetadata {
    private static final int MAX_IP_LITERAL_LENGTH = 45;
    private static final int MAX_USER_AGENT_CODE_POINTS = 512;
    private final String ipAddress;
    private final String userAgent;

    private LegalRequestMetadata(IpAddress ipAddress, String userAgent) {
        this.ipAddress = ipAddress.canonical();
        this.userAgent = userAgent;
    }

    /** Parses a literal without DNS, ports, zones, brackets or surrounding whitespace. */
    public static IpAddress parseIpLiteral(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > MAX_IP_LITERAL_LENGTH) {
            throw invalid();
        }
        for (int i = 0; i < literal.length(); i++) {
            char character = literal.charAt(i);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == ':' || character == '.')) {
                throw invalid();
            }
        }
        if (literal.indexOf(':') < 0) {
            return new IpAddress(parseIpv4(literal));
        }

        int[] groups = new int[8];
        int compression = literal.indexOf("::");
        if (compression < 0) {
            if (appendGroups(literal, groups, 0, true) != 8) throw invalid();
        } else {
            if (compression != literal.lastIndexOf("::")) throw invalid();
            int leftCount = appendGroups(literal.substring(0, compression), groups, 0, false);
            int[] right = new int[8];
            int rightCount = appendGroups(literal.substring(compression + 2), right, 0, true);
            if (leftCount + rightCount >= 8) throw invalid();
            System.arraycopy(right, 0, groups, 8 - rightCount, rightCount);
        }

        byte[] address = new byte[16];
        for (int i = 0; i < groups.length; i++) {
            address[i * 2] = (byte) (groups[i] >>> 8);
            address[i * 2 + 1] = (byte) groups[i];
        }
        if (isIpv4Mapped(address)) {
            address = Arrays.copyOfRange(address, 12, 16);
        }
        return new IpAddress(address);
    }

    /** Empty UA is absent; otherwise preserves exact Unicode scalar values without normalization. */
    public static LegalRequestMetadata of(IpAddress ipAddress, String nullableUserAgent) {
        if (ipAddress == null) throw invalid();
        String userAgent = nullableUserAgent;
        if (userAgent != null && userAgent.isEmpty()) userAgent = null;
        if (userAgent != null) {
            // One scalar occupies at most two UTF-16 units and four UTF-8 bytes.
            if (userAgent.length() > MAX_USER_AGENT_CODE_POINTS * 2) throw invalid();
            int codePoints = 0;
            for (int i = 0; i < userAgent.length();) {
                char character = userAgent.charAt(i++);
                if (Character.isHighSurrogate(character)) {
                    if (i == userAgent.length() || !Character.isLowSurrogate(userAgent.charAt(i++))) {
                        throw invalid();
                    }
                } else if (Character.isLowSurrogate(character) || Character.isISOControl(character)) {
                    throw invalid();
                }
                if (++codePoints > MAX_USER_AGENT_CODE_POINTS) throw invalid();
            }
        }
        return new LegalRequestMetadata(ipAddress, userAgent);
    }

    public String ipAddress() { return ipAddress; }
    public String userAgent() { return userAgent; }

    @Override public String toString() { return "LegalRequestMetadata[redacted]"; }

    /** Immutable normalized address; mapped IPv6 addresses use the IPv4 family. */
    public static final class IpAddress {
        private final byte[] address;
        private final String canonical;

        private IpAddress(byte[] address) {
            this.address = address.clone();
            this.canonical = address.length == 4 ? canonicalIpv4(address) : canonicalIpv6(address);
        }

        public String canonical() { return canonical; }
        public byte[] addressBytes() { return address.clone(); }
        public boolean isIpv4() { return address.length == 4; }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof IpAddress ip && Arrays.equals(address, ip.address);
        }
        @Override public int hashCode() { return Arrays.hashCode(address); }
        @Override public String toString() { return "IpAddress[redacted]"; }
    }

    private static byte[] parseIpv4(String literal) {
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4) throw invalid();
        byte[] result = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.charAt(0) == '0')) {
                throw invalid();
            }
            int value = 0;
            for (int j = 0; j < octet.length(); j++) {
                char digit = octet.charAt(j);
                if (digit < '0' || digit > '9') throw invalid();
                value = value * 10 + digit - '0';
            }
            if (value > 255) throw invalid();
            result[i] = (byte) value;
        }
        return result;
    }

    private static int appendGroups(String portion, int[] groups, int count, boolean allowDottedTail) {
        if (portion.isEmpty()) return count;
        String[] tokens = portion.split(":", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.indexOf('.') >= 0) {
                if (!allowDottedTail || i != tokens.length - 1 || count > 6) throw invalid();
                byte[] ipv4 = parseIpv4(token);
                groups[count++] = (Byte.toUnsignedInt(ipv4[0]) << 8) | Byte.toUnsignedInt(ipv4[1]);
                groups[count++] = (Byte.toUnsignedInt(ipv4[2]) << 8) | Byte.toUnsignedInt(ipv4[3]);
            } else {
                if (token.isEmpty() || token.length() > 4 || count == 8) throw invalid();
                int value = 0;
                for (int j = 0; j < token.length(); j++) {
                    char character = token.charAt(j);
                    int digit = character >= '0' && character <= '9' ? character - '0'
                            : character >= 'a' && character <= 'f' ? character - 'a' + 10
                            : character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
                    if (digit < 0) throw invalid();
                    value = (value << 4) | digit;
                }
                groups[count++] = value;
            }
        }
        return count;
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) if (address[i] != 0) return false;
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    private static String canonicalIpv4(byte[] address) {
        return Byte.toUnsignedInt(address[0]) + "." + Byte.toUnsignedInt(address[1]) + "."
                + Byte.toUnsignedInt(address[2]) + "." + Byte.toUnsignedInt(address[3]);
    }

    private static String canonicalIpv6(byte[] address) {
        int[] groups = new int[8];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = (Byte.toUnsignedInt(address[i * 2]) << 8) | Byte.toUnsignedInt(address[i * 2 + 1]);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < groups.length;) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < groups.length && groups[i] == 0) i++;
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder result = new StringBuilder(39);
        for (int i = 0; i < groups.length;) {
            if (i == bestStart) {
                result.append("::");
                i += bestLength;
            } else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
                result.append(Integer.toHexString(groups[i++]));
            }
        }
        return result.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Metadata legal inválida");
    }
}
