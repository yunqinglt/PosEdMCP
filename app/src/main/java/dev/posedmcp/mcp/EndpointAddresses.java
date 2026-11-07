package dev.posedmcp.mcp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Connectable IPv4 URLs; a wildcard bind address is never offered as a client URL. */
public final class EndpointAddresses {
    public static String bindAddress(boolean lan) { return lan ? "0.0.0.0" : "127.0.0.1"; }

    public static List<String> urls(int port, boolean lan) {
        TreeSet<String> local = new TreeSet<>(), other = new TreeSet<>();
        if (lan) try {
            java.util.Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            if (networks == null) return Collections.singletonList(url("127.0.0.1", port));
            for (NetworkInterface network : Collections.list(networks)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || address.isAnyLocalAddress()) continue;
                    (address.isSiteLocalAddress() ? local : other).add(url(address.getHostAddress(), port));
                }
            }
        } catch (SocketException | SecurityException ignored) {}
        List<String> urls = new ArrayList<>(local);
        urls.addAll(other);
        urls.add(url("127.0.0.1", port));
        return urls;
    }

    private static String url(String host, int port) { return "http://" + host + ":" + port + "/mcp"; }
    private EndpointAddresses() {}
}
