package io.vodroid.core;

import java.io.IOException;
import java.net.*;
import java.util.*;

public final class EpdgDiscovery {
    private EpdgDiscovery() {}

    public static List<String> hosts(String override, SimCard.Identity identity, CarrierProfile profile) throws IOException {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        if (override != null && !override.isBlank()) {
            for (String host : override.split("[,;\\s]+")) hosts.add(normalize(host));
        } else {
            if (!profile.epdg().isEmpty()) for(String configured:profile.epdg().split("[,;\\s]+")) {
                // Optional ROM hints must not suppress standard discovery when
                // malformed or encoded in an unsupported form (e.g. a scoped IP).
                if(hosts.size()>=6)break;
                try{hosts.add(normalize(configured));}catch(IOException ignored){}
            }
            hosts.add(identity.epdg());
            // Some deployed DNS zones use two MNC digits (also supported by VoCat).
            if (identity.mnc().length() == 2) hosts.add(identity.epdg().replace(".mnc0" + identity.mnc() + ".", ".mnc" + identity.mnc() + "."));
        }
        if (hosts.isEmpty() || hosts.size() > 8) throw new IOException("ePDG requires 1–8 hostnames");
        return List.copyOf(hosts);
    }

    static String normalize(String host) throws IOException {
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) throw new IOException("Invalid ePDG hostname");
        for (String label : host.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IOException("Invalid ePDG hostname");
        return host;
    }

    static List<InetAddress> addresses(InetAddress[] resolved) throws IOException {
        LinkedHashSet<InetAddress> ordered = new LinkedHashSet<>();
        // Preserve DNS order within each family and try every family on a failed route.
        for (boolean ipv4 : new boolean[]{true, false}) for (InetAddress address : resolved)
            if (address != null && (address instanceof Inet4Address) == ipv4 &&
                    !address.isAnyLocalAddress() && !address.isMulticastAddress()) ordered.add(address);
        if (ordered.isEmpty()) throw new UnknownHostException("ePDG DNS returned no usable IP endpoint");
        return List.copyOf(ordered);
    }
}
