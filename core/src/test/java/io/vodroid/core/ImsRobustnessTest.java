package io.vodroid.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;

public class ImsRobustnessTest {
    private static byte[] response(SipMessage request,int code,String... extra){
        List<String> headers=new ArrayList<>(List.of("Via: "+request.header("via"),"From: "+request.header("from"),"To: "+request.header("to"),"Call-ID: "+request.header("call-id"),"CSeq: "+request.header("cseq")));headers.addAll(List.of(extra));
        return SipMessage.encode("SIP/2.0 "+code+" Test",headers,new byte[0]);
    }
    private static final ImsSession.Listener QUIET=new ImsSession.Listener(){public void state(String value){}public void sms(String from,String text){}public void smsStatus(String id,String status){}public void call(ImsSession.Call call){}public void audio(byte[] data,RtpEndpoint.Format format){}};
    private static final class Fixture implements AutoCloseable {
        final PacketNetwork[] network=new PacketNetwork[2];final PacketNetwork.Port peer;final ImsSession ims;
        Fixture()throws Exception{
            network[0]=new PacketNetwork("10.9.0.1",packet->network[1].receive(packet));network[1]=new PacketNetwork("10.9.0.2",packet->network[0].receive(packet));peer=network[1].openUdp(5060);
            ims=new ImsSession(new ImsSession.Config("10.9.0.2",5060,false,"",false,""),network[0],null,new SimCard.Identity("001010123456789","8901","001","01"),QUIET);
        }
        SipMessage answer(int code,String... extra)throws Exception{PacketNetwork.Datagram packet=peer.receive(2000);SipMessage message=SipMessage.parse(packet.data());peer.send(packet.host(),packet.port(),response(message,code,extra));return message;}
        public void close(){ims.close();network[0].close();network[1].close();}
    }
    @Test public void intervalTooBriefRetriesWithCarrierMinimum()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Fixture f=new Fixture()){
            Future<?> registration=worker.submit(()->{try{f.ims.register();}catch(Exception error){throw new RuntimeException(error);}});
            assertEquals("600",f.answer(423,"Min-Expires: 1200").header("expires"));assertEquals("1200",f.answer(200,"Expires: 1200").header("expires"));
            registration.get(2,TimeUnit.SECONDS);assertTrue(f.ims.registered());
        }finally{worker.shutdownNow();}
    }
    @Test public void contactExpiryWinsAndTemporaryRenewalFailureRetriesWithinLease()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Fixture f=new Fixture()){
            Future<?> registration=worker.submit(()->{try{f.ims.register();}catch(Exception error){throw new RuntimeException(error);}});
            PacketNetwork.Datagram packet=f.peer.receive(2000);SipMessage first=SipMessage.parse(packet.data());
            f.peer.send(packet.host(),packet.port(),response(first,200,"Expires: 600","Contact: "+first.header("contact")+";expires=20"));registration.get(2,TimeUnit.SECONDS);
            PacketNetwork.Datagram refresh=f.peer.receive(18000);SipMessage request=SipMessage.parse(refresh.data());
            assertTrue(f.ims.registered());f.peer.send(refresh.host(),refresh.port(),response(request,503,"Retry-After: 1"));
            assertTrue(f.ims.registered());f.answer(200,"Expires: 600");
            assertThrows(java.net.SocketTimeoutException.class,()->f.peer.receive(100));assertTrue(f.ims.registered());
        }finally{worker.shutdownNow();}
    }
    @Test public void expiryParsingHonorsOnlyOurContactAndRejectsInvalidLease()throws Exception{
        String contact="<sip:subscriber@10.9.0.1:5000>";
        SipMessage reply=SipMessage.parse(SipMessage.encode("SIP/2.0 200 OK",List.of("Expires: 600","Contact: <sip:other@10.9.0.1:5001>;expires=1", "Contact: "+contact+";expires=15"),new byte[0]));
        assertEquals(15,ImsSession.registrationExpiry(reply,contact,600));
        reply=SipMessage.parse(SipMessage.encode("SIP/2.0 200 OK",List.of("Expires: 0"),new byte[0]));assertEquals(0,ImsSession.registrationExpiry(reply,contact,600));
        SipMessage invalid=SipMessage.parse(SipMessage.encode("SIP/2.0 200 OK",List.of("Expires: bad"),new byte[0]));assertThrows(IOException.class,()->ImsSession.registrationExpiry(invalid,contact,600));
    }
    @Test public void tcpKeepalivesDoNotRecurseOrCorruptFollowingSipMessage()throws Exception{
        byte[] message=SipMessage.encode("SIP/2.0 200 OK",List.of(),new byte[0]);ByteArrayOutputStream wire=new ByteArrayOutputStream();
        for(int n=0;n<20000;n++)wire.write("\r\n".getBytes(StandardCharsets.US_ASCII));wire.write(message);
        ByteArrayInputStream stream=new ByteArrayInputStream(wire.toByteArray());assertEquals(200,ImsSession.readTcpMessage(stream).code());assertNull(ImsSession.readTcpMessage(stream));
        assertThrows(IOException.class,()->ImsSession.readTcpMessage(new ByteArrayInputStream("SIP/2.0 200 OK\r\n".getBytes(StandardCharsets.US_ASCII))));
        byte[] duplicate="SIP/2.0 200 OK\r\nContent-Length: 0\r\nl: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);assertThrows(IOException.class,()->ImsSession.readTcpMessage(new ByteArrayInputStream(duplicate)));
    }
}
