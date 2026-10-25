package io.vodroid.core;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import static org.junit.Assert.*;

public class IkeEndpointTest {
    @Test public void supportsIpv6OnlyOuterNetworksAndPrefersIpv4WhenAvailable() throws Exception {
        InetAddress ipv6=InetAddress.getByName("2001:db8::9"),ipv4=InetAddress.getByAddress(new byte[]{(byte)192,0,2,9});
        assertEquals(ipv6,IkeSession.selectEndpoint(new InetAddress[]{ipv6}));
        assertEquals(ipv4,IkeSession.selectEndpoint(new InetAddress[]{ipv6,ipv4}));
        assertThrows(IOException.class,()->IkeSession.selectEndpoint(new InetAddress[0]));
    }
}
