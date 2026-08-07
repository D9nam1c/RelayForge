package io.relayforge.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves every address and rejects local, private, link-local, multicast, and reserved targets. */
public final class OutboundTargetPolicy {
    @FunctionalInterface
    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    public record ValidatedTarget(URI canonicalUri, List<InetAddress> resolvedAddresses) {}

    private final Set<Integer> allowedPorts;
    private final boolean developmentSinkAllowed;
    private final String developmentSinkHost;
    private final Resolver resolver;

    public OutboundTargetPolicy(Set<Integer> allowedPorts, boolean developmentSinkAllowed, String developmentSinkHost) {
        this(allowedPorts, developmentSinkAllowed, developmentSinkHost, InetAddress::getAllByName);
    }

    OutboundTargetPolicy(Set<Integer> allowedPorts, boolean developmentSinkAllowed, String developmentSinkHost,
                         Resolver resolver) {
        this.allowedPorts = Set.copyOf(allowedPorts);
        this.developmentSinkAllowed = developmentSinkAllowed;
        this.developmentSinkHost = developmentSinkHost.toLowerCase(Locale.ROOT);
        this.resolver = resolver;
    }

    public ValidatedTarget validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl).normalize();
        } catch (IllegalArgumentException exception) {
            throw new BlockedTargetException("Malformed endpoint URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new BlockedTargetException("Endpoint URL must contain only a host, path, and optional query");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean devException = developmentSinkAllowed && normalizedHost.equals(developmentSinkHost);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(devException && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new BlockedTargetException("HTTPS is required");
        }
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        if (!allowedPorts.contains(port) && !devException) throw new BlockedTargetException("Endpoint port is not allowed");
        List<InetAddress> addresses;
        try {
            addresses = Arrays.asList(resolver.resolve(normalizedHost));
        } catch (UnknownHostException exception) {
            throw new BlockedTargetException("Endpoint host does not resolve");
        }
        if (addresses.isEmpty()) throw new BlockedTargetException("Endpoint host does not resolve");
        if (!devException && addresses.stream().anyMatch(OutboundTargetPolicy::isForbidden)) {
            throw new BlockedTargetException("Endpoint resolves to a non-public address");
        }
        URI canonical;
        try {
            canonical = new URI(uri.getScheme().toLowerCase(Locale.ROOT), null, normalizedHost, uri.getPort(),
                    uri.getRawPath(), uri.getRawQuery(), null);
        } catch (java.net.URISyntaxException exception) {
            throw new BlockedTargetException("Endpoint URL cannot be canonicalized");
        }
        return new ValidatedTarget(canonical, List.copyOf(addresses));
    }

    public void revalidate(ValidatedTarget original) {
        ValidatedTarget current = validate(original.canonicalUri().toString());
        if (!Set.copyOf(current.resolvedAddresses()).equals(Set.copyOf(original.resolvedAddresses()))) {
            throw new BlockedTargetException("Endpoint DNS changed during delivery");
        }
    }

    static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51)
                    || (first == 203 && second == 0);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            int fourth = Byte.toUnsignedInt(bytes[3]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean ietfSpecial = first == 0x20 && second == 0x01 && third <= 0x01;
            boolean documentation = first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8;
            boolean sixToFour = first == 0x20 && second == 0x02;
            return uniqueLocal || ietfSpecial || documentation || sixToFour;
        }
        return true;
    }

    public static final class BlockedTargetException extends RuntimeException {
        public BlockedTargetException(String message) { super(message); }
    }
}
