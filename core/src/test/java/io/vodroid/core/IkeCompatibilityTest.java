package io.vodroid.core;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static io.vodroid.core.Bytes.*;
import static io.vodroid.core.IkeWire.*;
import static org.junit.Assert.*;

/** Actual UDP SA_INIT exchanges with a local ePDG; no SIM credentials or Internet. */
public class IkeCompatibilityTest {
    private static final SimCard.Identity ID=new SimCard.Identity("001010123456789","8901","001","01");
    private static final InetAddress LOOP=InetAddress.getLoopbackAddress();
    private record Request(DatagramPacket packet,Message message) {}
    private Request receive(DatagramSocket server,boolean natt)throws Exception {
        byte[] data=new byte[4096];DatagramPacket packet=new DatagramPacket(data,data.length);server.receive(packet);
        byte[] wire=cut(data,0,packet.getLength());if(natt){assertEquals(0,u32(wire,0));wire=cut(wire,4,wire.length-4);}
        return new Request(packet,decode(wire));
    }
    private void respond(DatagramSocket server,Request request,int type,byte[] data,boolean natt)throws Exception {
        byte[] wire=encode(request.message.initiator(),new byte[8],34,0x20,0,List.of(p(41,IkeWire.notify(type,data))));
        if(natt)wire=cat(new byte[4],wire);
        server.send(new DatagramPacket(wire,wire.length,request.packet.getSocketAddress()));
    }
    private int group(Request request)throws Exception{return u16(get(parse(request.message.first(),request.message.body()),34),0);}
    private List<Payload> payloads(Request request)throws Exception{return parse(request.message.first(),request.message.body());}
    private IkeSession session(DatagramSocket initial,int natt,IkePolicy policy,List<String> log,InetAddress... addresses){
        return new IkeSession(socket->{},log::add,host->addresses,policy,CarrierProfile.STANDARD,initial.getLocalPort(),natt,15);
    }

    @Test public void autoHandlesCookieThenOneExplicitLegacyFallback()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();List<String> log=new CopyOnWriteArrayList<>();
        try(DatagramSocket server=new DatagramSocket(0,LOOP)){
            server.setSoTimeout(2000);
            Future<?> peer=worker.submit(()->{try{
                Request first=receive(server,false);assertEquals(14,group(first));respond(server,first,16390,b(42),false);
                Request cookie=receive(server,false);assertArrayEquals(first.message.initiator(),cookie.message.initiator());
                assertArrayEquals(get(payloads(first),40),get(payloads(cookie),40));assertArrayEquals(b(42),IkeSession.readCookie(payloads(cookie)));
                respond(server,cookie,14,new byte[0],false);
                Request legacy=receive(server,false);assertEquals(2,group(legacy));assertFalse(Arrays.equals(first.message.initiator(),legacy.message.initiator()));
                assertFalse(Arrays.equals(get(payloads(first),40),get(payloads(legacy),40)));respond(server,legacy,24,new byte[0],false);
            }catch(Exception error){throw new RuntimeException(error);}});
            try(IkeSession ike=session(server,server.getLocalPort(),IkePolicy.AUTO,log,LOOP)){
                IOException error=assertThrows(IOException.class,()->ike.connect("epdg.test",ID,null));assertTrue(error.getMessage().contains("24"));
            }
            peer.get(3,TimeUnit.SECONDS);assertEquals(1,log.stream().filter(value->value.contains("尝试兼容套件")).count());
        }finally{worker.shutdownNow();}
    }

    @Test public void modernDoesNotFallBackAndAuthenticationErrorsNeverRotate()throws Exception {
        for(int notify:new int[]{14,24}){
            ExecutorService worker=Executors.newSingleThreadExecutor();List<String> log=new CopyOnWriteArrayList<>();
            try(DatagramSocket server=new DatagramSocket(0,LOOP)){
                server.setSoTimeout(2000);
                Future<?> peer=worker.submit(()->{try{Request request=receive(server,false);assertEquals(14,group(request));respond(server,request,notify,new byte[0],false);}catch(Exception error){throw new RuntimeException(error);}});
                try(IkeSession ike=session(server,server.getLocalPort(),IkePolicy.MODERN,log,LOOP)){
                    assertThrows(IOException.class,()->ike.connect("epdg.test",ID,null));
                }
                peer.get(3,TimeUnit.SECONDS);assertFalse(log.stream().anyMatch(value->value.contains("MODP2")));
            }finally{worker.shutdownNow();}
        }
    }

    @Test public void unreachableFirstAddressRotatesToNextDnsAddress()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();List<String> log=new CopyOnWriteArrayList<>();
        try(DatagramSocket server=new DatagramSocket(0,LOOP)){
            server.setSoTimeout(2000);
            Future<?> peer=worker.submit(()->{try{Request request=receive(server,false);respond(server,request,24,new byte[0],false);}catch(Exception error){throw new RuntimeException(error);}});
            try(IkeSession ike=session(server,server.getLocalPort(),IkePolicy.AUTO,log,InetAddress.getByName("127.0.0.2"),LOOP)){
                IOException error=assertThrows(IOException.class,()->ike.connect("epdg.test",ID,null));assertTrue(error.getMessage().contains("24"));
            }
            peer.get(3,TimeUnit.SECONDS);assertEquals(2,log.stream().filter(value->value.startsWith("ePDG 地址 ")).count());
        }finally{worker.shutdownNow();}
    }

    @Test public void silentInitialPortTriesNatTWithoutAlgorithmDowngrade()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();List<String> log=new CopyOnWriteArrayList<>();
        try(DatagramSocket silent=new DatagramSocket(0,LOOP);DatagramSocket natt=new DatagramSocket(0,LOOP)){
            natt.setSoTimeout(2000);
            Future<?> peer=worker.submit(()->{try{Request request=receive(natt,true);assertEquals(14,group(request));respond(natt,request,24,new byte[0],true);}catch(Exception error){throw new RuntimeException(error);}});
            try(IkeSession ike=session(silent,natt.getLocalPort(),IkePolicy.AUTO,log,LOOP)){
                assertThrows(IOException.class,()->ike.connect("epdg.test",ID,null));
            }
            peer.get(3,TimeUnit.SECONDS);assertTrue(log.stream().anyMatch(value->value.contains("NAT-T 4500")));assertFalse(log.stream().anyMatch(value->value.contains("MODP2")));
        }finally{worker.shutdownNow();}
    }

    @Test public void policyAndDiscoveryPreserveManualChoiceAndCarrierScope()throws Exception {
        CarrierProfile legacy=new CarrierProfile("matched-carrier","EPDG.CARRIER.TEST.,backup.carrier.test",true);
        assertEquals(2,IkePolicy.AUTO.initialGroup(legacy));assertEquals(14,IkePolicy.MODERN.initialGroup(legacy));assertEquals(2,IkePolicy.LEGACY.initialGroup(CarrierProfile.STANDARD));
        assertFalse(IkePolicy.LEGACY.allowsLegacyFallback(2));assertFalse(IkePolicy.MODERN.allowsLegacyFallback(14));assertTrue(IkePolicy.AUTO.allowsLegacyFallback(14));
        assertEquals(List.of("manual.test"),EpdgDiscovery.hosts("MANUAL.TEST.,manual.test",ID,legacy));
        List<String> automatic=EpdgDiscovery.hosts("",ID,legacy);assertEquals("epdg.carrier.test",automatic.get(0));assertTrue(automatic.contains(ID.epdg()));assertTrue(automatic.contains("epdg.epc.mnc01.mcc001.pub.3gppnetwork.org"));
        for(String invalid:List.of("https://epdg.test","epdg..test","-epdg.test","a@epdg.test"))assertThrows(IOException.class,()->EpdgDiscovery.hosts(invalid,ID,legacy));
        List<String> mixed=EpdgDiscovery.hosts("",ID,new CarrierProfile("rom-hints","https://bad.test,good.test,[fe80::1%wlan0]",false));
        assertEquals("good.test",mixed.get(0));assertTrue(mixed.contains(ID.epdg()));
        InetAddress ipv6=InetAddress.getByName("2001:db8::2");assertEquals(List.of(LOOP,ipv6),EpdgDiscovery.addresses(new InetAddress[]{ipv6,LOOP,ipv6}));
    }

    @Test public void malformedNotificationsCannotTriggerDowngrade()throws Exception {
        assertFalse(IkeSession.legacyNegotiationRequested(List.of(p(41,IkeWire.notify(14,new byte[0])),p(41,IkeWire.notify(24,new byte[0])))));
        assertFalse(IkeSession.legacyNegotiationRequested(List.of(p(41,hex("0100000E")))));
        assertEquals(14,IkeSession.negotiationGroupRequested(List.of(p(41,IkeWire.notify(17,n16(14)))),2));
        assertEquals(0,IkeSession.negotiationGroupRequested(List.of(p(41,IkeWire.notify(17,n16(19)))),14));
        assertThrows(IOException.class,()->IkeSession.readCookie(List.of(p(41,IkeWire.notify(16390,new byte[0])))));
        assertThrows(IOException.class,()->IkeSession.readCookie(List.of(p(41,IkeWire.notify(16390,new byte[65])))));
        assertThrows(IOException.class,()->IkeSession.readCookie(List.of(p(41,IkeWire.notify(16390,b(1))),p(41,IkeWire.notify(16390,b(2))))));
    }

    @Test public void trafficSelectorPortsCountsAndUnionAreValidated()throws Exception {
        byte[] valid=trafficSelector();IkeSession.validateSelectors(valid,"10.0.0.1",true);
        byte[] port=valid.clone();port[9]=1;assertThrows(IOException.class,()->IkeSession.validateSelectors(port,"10.0.0.1",true));
        byte[] count=valid.clone();count[0]=2;assertThrows(IOException.class,()->IkeSession.validateSelectors(count,"10.0.0.1",true));
        byte[] other=cat(b(7,0,0,16,0,0,255,255),hex("C0000201C0000201"));
        IkeSession.validateSelectors(cat(b(2,0,0,0),other,cut(valid,4,16)),"10.0.0.1",true);
    }
}
