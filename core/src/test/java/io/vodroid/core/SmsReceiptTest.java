package io.vodroid.core;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;

/** Independent gateway traffic: verify the report on the wire, not only a local callback. */
public class SmsReceiptTest {
    private static final class Peer implements AutoCloseable {
        final PacketNetwork[] net=new PacketNetwork[2];final PacketNetwork.Port gateway;final ImsSession ims;
        final BlockingQueue<String> events=new LinkedBlockingQueue<>(),ids=new LinkedBlockingQueue<>();final List<String> bodies=new CopyOnWriteArrayList<>();
        int port,clientPort;String contact;boolean rejectSave;
        Peer()throws Exception{
            net[0]=new PacketNetwork("10.31.0.1",p->net[1].receive(p));net[1]=new PacketNetwork("10.31.0.2",p->net[0].receive(p));gateway=net[1].openUdp(5060);
            SimCard card=new SimCard(new SimCard.Transport(){public int open(byte[] aid){return 1;}public byte[] transmit(int channel,byte[] raw){throw new AssertionError("No SIM calls in fixture");}public void closeChannel(int channel){}public void close(){}});
            ims=new ImsSession(new ImsSession.Config("10.31.0.2",5060,false,"",false,""),net[0],card,new SimCard.Identity("234150123456789","fake-card","234","15"),new ImsSession.Listener(){
                public void state(String s){events.add(s);}public void sms(String from,String text){throw new AssertionError("Stable delivery identity was not provided");}
                public void sms(String id,String from,String text){if(rejectSave)throw new IllegalStateException("Storage unavailable");ids.add(id);bodies.add(text);}
                public void smsStatus(String id,String status){}public void call(ImsSession.Call call){}public void audio(byte[] data,RtpEndpoint.Format format){}
            });
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<?> registration=worker.submit(()->{try{ims.register();}catch(Exception e){throw new RuntimeException(e);}});PacketNetwork.Datagram d=gateway.receive(1000);clientPort=d.port();SipMessage register=SipMessage.parse(d.data());contact=SipMessage.uri(register.header("contact"));port=Integer.parseInt(contact.substring(contact.indexOf(':',4)+1).split(";",2)[0]);
                List<String> h=responseHeaders(register);h.add("P-Associated-URI: <sip:subscriber@ims.test>, <tel:+12025550123>");h.add("Service-Route: <sip:route.ims.test;lr>");gateway.send(d.host(),d.port(),SipMessage.encode("SIP/2.0 200 OK",h,b()));registration.get(2,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
        byte[] deliver(String id,String branch,String extra,byte[] body){return SipMessage.encode("MESSAGE "+contact+" SIP/2.0",List.of("Via: SIP/2.0/UDP 10.31.0.2:5060;branch="+branch+extra,"From: <sip:fallback-gw@ims.test>;tag=network","To: <sip:subscriber@ims.test>","P-Asserted-Identity: <sip:actual-gw@ims.test>","P-Called-Party-ID: <tel:+12025550123>","Call-ID: "+id,"CSeq: 1 MESSAGE","Content-Type: application/vnd.3gpp.sms"),body);}
        void send(byte[] request)throws Exception{gateway.send("10.31.0.1",port,request);}
        PacketNetwork.Datagram receive()throws Exception{return gateway.receive(1500);}
        void ok(PacketNetwork.Datagram d)throws Exception{SipMessage request=SipMessage.parse(d.data());gateway.send(d.host(),d.port(),SipMessage.encode("SIP/2.0 200 OK",responseHeaders(request),b()));}
        public void close(){ims.close();net[0].close();net[1].close();}
    }
    private static List<String> responseHeaders(SipMessage request){return new ArrayList<>(List.of("Via: "+request.header("via"),"From: "+request.header("from"),"To: "+request.header("to"),"Call-ID: "+request.header("call-id"),"CSeq: "+request.header("cseq")));}
    private static byte[] sms(int reference,String text){byte[] encoded=text.getBytes(StandardCharsets.UTF_16BE),tp=cat(b(0,4,0x91),hex("2143"),b(0,8),new byte[7],b(encoded.length),encoded);return cat(b(1,reference,0,0,tp.length),tp);}
    @Test public void ackTargetsAssertedGatewayWithoutSmscAndReferencesOriginalMessage()throws Exception{
        try(Peer p=new Peer()){
            p.send(p.deliver("delivery-1","z9hG4bK-a","",sms(17,"hello")));assertEquals(200,SipMessage.parse(p.receive().data()).code());PacketNetwork.Datagram d=p.receive();SipMessage ack=SipMessage.parse(d.data());
            assertEquals("MESSAGE sip:actual-gw@ims.test SIP/2.0",ack.start);assertArrayEquals(hex("0211"),ack.body);assertEquals("delivery-1",ack.header("in-reply-to"));assertNotEquals("delivery-1",ack.header("call-id"));assertEquals("<tel:+12025550123>",ack.header("p-preferred-identity"));assertEquals("<sip:route.ims.test;lr>",ack.header("route"));assertEquals("no-fork",ack.header("request-disposition"));p.ok(d);assertNotNull(p.ids.poll(1,TimeUnit.SECONDS));
        }
    }
    @Test public void alteredViaParametersRetransmitSipReplyWithoutSecondDelivery()throws Exception{
        try(Peer p=new Peer()){
            p.send(p.deliver("delivery-1","z9hG4bK-a",";rport",sms(17,"hello")));byte[] reply=p.receive().data();PacketNetwork.Datagram ack=p.receive();p.ok(ack);assertNotNull(p.ids.poll(1,TimeUnit.SECONDS));
            p.send(p.deliver("delivery-1","z9hG4bK-a",";received=10.31.0.2;rport=5060",sms(17,"hello")));assertArrayEquals(reply,p.receive().data());assertNull(p.ids.poll(80,TimeUnit.MILLISECONDS));
        }
    }
    @Test public void lostAckResponseRetransmitsSameReportAndOnlyPersistsOnce()throws Exception{
        try(Peer p=new Peer()){
            p.send(p.deliver("delivery-1","z9hG4bK-a","",sms(17,"hello")));p.receive();PacketNetwork.Datagram first=p.receive(),retry=p.receive();assertArrayEquals(first.data(),retry.data());p.ok(retry);assertNotNull(p.ids.poll(1,TimeUnit.SECONDS));assertNull(p.ids.poll(80,TimeUnit.MILLISECONDS));
        }
    }
    @Test public void sameTextInDifferentNetworkTransactionsRemainsDistinct()throws Exception{
        try(Peer p=new Peer()){
            for(int n=0;n<2;n++){p.send(p.deliver("delivery-"+n,"z9hG4bK-"+n,"",sms(17,"same text")));p.receive();p.ok(p.receive());}
            assertNotEquals(p.ids.poll(1,TimeUnit.SECONDS),p.ids.poll(1,TimeUnit.SECONDS));assertEquals(List.of("same text","same text"),p.bodies);
        }
    }
    @Test public void invalidTpduAndStorageFailureReturnRpErrorInsteadOfFalseAck()throws Exception{
        for(boolean storage:new boolean[]{false,true})try(Peer p=new Peer()){
            p.rejectSave=storage;p.send(p.deliver("delivery-1","z9hG4bK-a","",storage?sms(17,"hello"):b(1,17,0,0,1,255)));assertEquals(200,SipMessage.parse(p.receive().data()).code());PacketNetwork.Datagram d=p.receive();assertArrayEquals(b(4,17,1,storage?22:95),SipMessage.parse(d.data()).body);p.ok(d);assertTrue(p.ids.isEmpty());
        }
    }
    @Test public void rejectedAckIsReportedAsFailureInsteadOfSuccess()throws Exception{
        try(Peer p=new Peer()){
            p.send(p.deliver("delivery-1","z9hG4bK-a","",sms(17,"hello")));p.receive();PacketNetwork.Datagram d=p.receive();SipMessage request=SipMessage.parse(d.data());p.gateway.send(d.host(),d.port(),SipMessage.encode("SIP/2.0 403 Forbidden",responseHeaders(request),b()));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);String result="";while(System.nanoTime()<end){String event=p.events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&event.contains("RP-ACK")){result=event;break;}}assertTrue(result.contains("失败"));assertTrue(result.contains("403"));
        }
    }
    @Test public void simultaneousRetransmissionOnBothSipSocketsDoesNotDoubleDeliver()throws Exception{
        try(Peer p=new Peer()){
            byte[] request=p.deliver("delivery-1","z9hG4bK-a","",sms(17,"hello"));p.send(request);p.gateway.send("10.31.0.1",p.clientPort,request);int replies=0,reports=0;
            for(int n=0;n<3;n++){PacketNetwork.Datagram d=p.receive();if(SipMessage.parse(d.data()).code()==200)replies++;else{reports++;p.ok(d);}}
            assertEquals(2,replies);assertEquals(1,reports);assertNotNull(p.ids.poll(1,TimeUnit.SECONDS));assertNull(p.ids.poll(80,TimeUnit.MILLISECONDS));
        }
    }
}
