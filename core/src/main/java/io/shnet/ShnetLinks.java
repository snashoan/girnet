package io.shnet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ShnetLinks {
    private ShnetLinks() {
    }

    public static List<Shnet.Link> discover(int port, boolean tls, Shnet.LinkPreference preference) {
        String scheme = tls ? "https" : "http";
        List<Shnet.Link> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            ifaces.sort((a, b) -> {
                boolean aVirt = isVirtualOrContainer(a.getName());
                boolean bVirt = isVirtualOrContainer(b.getName());
                if (aVirt != bVirt) {
                    return aVirt ? 1 : -1;
                }
                return a.getName().compareTo(b.getName());
            });

            for (NetworkInterface iface : ifaces) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address && address.isLinkLocalAddress()) {
                        continue;
                    }
                    addLink(address, iface.getName(), port, scheme, results, seen);
                }
            }
        } catch (SocketException ignored) {
        }

        // On Android / PRoot, /proc/net/if_inet6 may be blocked by SELinux, causing NetworkInterface
        // to return no IPv6 addresses. Fall back to parsing 'ip -6 -o addr show' via netlink.
        boolean hasIpv6 = false;
        for (Shnet.Link link : results) {
            if (link.isIpv6) {
                hasIpv6 = true;
                break;
            }
        }
        if (!hasIpv6) {
            discoverIpv6ViaIpCommand(port, scheme, results, seen);
        }

        if (results.isEmpty()) {
            results.add(new Shnet.Link("localhost IPv6", scheme + "://[::1]:" + port + "/", true, "lo", null));
            results.add(new Shnet.Link("localhost IPv4", scheme + "://127.0.0.1:" + port + "/", false, "lo", null));
        }

        return orderLinks(results, preference);
    }

    private static void discoverIpv6ViaIpCommand(int port, String scheme, List<Shnet.Link> results, Set<String> seen) {
        try {
            Process process = new ProcessBuilder("ip", "-6", "-o", "addr", "show").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.trim().split("\\s+");
                    // Format: 31: wlan1 inet6 2409:4055:2e3d:88c4::d5/64 scope global
                    if (tokens.length >= 6 && "inet6".equalsIgnoreCase(tokens[2])) {
                        String iface = tokens[1];
                        if (iface.endsWith(":")) iface = iface.substring(0, iface.length() - 1);
                        if (isVirtualOrContainer(iface)) continue;
                        String cidrAddr = tokens[3];
                        int slash = cidrAddr.indexOf('/');
                        String host = slash > 0 ? cidrAddr.substring(0, slash) : cidrAddr;
                        String scope = tokens[5];
                        if ("host".equalsIgnoreCase(scope) || "::1".equals(host)) {
                            continue;
                        }
                        boolean isLinkLocal = "link".equalsIgnoreCase(scope) || host.toLowerCase().startsWith("fe80");
                        try {
                            InetAddress addr = InetAddress.getByName(host);
                            String suffix = isLinkLocal ? "IPv6 (link-local)" : "IPv6";
                            String url = scheme + "://[" + host + "]:" + port + "/";
                            String key = "v6-" + host;
                            if (seen.add(key)) {
                                results.add(new Shnet.Link(labelForInterface(iface, suffix), url, true, iface, addr));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
    }

    private static boolean isVirtualOrContainer(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.startsWith("docker") || lower.startsWith("br-") || lower.startsWith("veth")
                || lower.startsWith("virbr") || lower.startsWith("cni");
    }

    private static void addLink(InetAddress address, String ifaceName, int port, String scheme,
                                List<Shnet.Link> results, Set<String> seen) {
        if (address instanceof Inet4Address) {
            String host = address.getHostAddress();
            String url = scheme + "://" + host + ":" + port + "/";
            String key = "v4-" + host;
            if (seen.add(key)) {
                results.add(new Shnet.Link(labelForInterface(ifaceName, "IPv4"), url, false, ifaceName, address));
            }
        } else if (address instanceof Inet6Address) {
            String host = address.getHostAddress();
            int zoneIndex = host.indexOf('%');
            if (zoneIndex >= 0) {
                host = host.substring(0, zoneIndex) + "%25" + host.substring(zoneIndex + 1);
            }
            String url = scheme + "://[" + host + "]:" + port + "/";
            String key = "v6-" + host;
            if (seen.add(key)) {
                String suffix = address.isLinkLocalAddress() ? "IPv6 (link-local)" : "IPv6";
                results.add(new Shnet.Link(labelForInterface(ifaceName, suffix), url, true, ifaceName, address));
            }
        }
    }

    private static String labelForInterface(String ifaceName, String suffix) {
        if (ifaceName == null || ifaceName.trim().isEmpty()) {
            return "Active " + suffix;
        }
        return ifaceName + " " + suffix;
    }

    private static int rankAddress(Shnet.Link link) {
        if (link.isIpv6) {
            if (link.address != null && link.address.isLinkLocalAddress()) {
                return 1;
            }
            return 0;
        }
        return 2;
    }

    private static List<Shnet.Link> orderLinks(List<Shnet.Link> links, Shnet.LinkPreference preference) {
        List<Shnet.Link> copy = new ArrayList<>(links);
        if (preference == Shnet.LinkPreference.IPV4_FIRST) {
            copy.sort((a, b) -> {
                int rA = a.isIpv6 ? 1 : 0;
                int rB = b.isIpv6 ? 1 : 0;
                return Integer.compare(rA, rB);
            });
        } else {
            copy.sort((a, b) -> Integer.compare(rankAddress(a), rankAddress(b)));
        }
        return copy;
    }
}
