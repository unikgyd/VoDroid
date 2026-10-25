package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

public class CallTest {
    @Test public void outboundCallExchangesSdpAckBidirectionalRtpAndBye() throws Exception {
        PacketNetwork[] net=new PacketNetwork[2];net[0]=new PacketNetwork("10.8.0.1",p->net[1].receive(p));net[1]=new PacketNetwork("10.8.0.2",p->net[0].receive(p));PacketNetwork.Port signal=net[1].openUdp(5060),media=net[1].openUdp(31000);BlockingQueue<ImsSession.Call> calls=new LinkedBlockingQueue<>();BlockingQueue<byte[]> audio=new LinkedBlockingQueue<>();
        SimCard.Transport card=new SimCard.Transport(){public int open(byte[] a){return 1;}public byte[] transmit(int c,byte[] a){throw new AssertionError();}public void closeChannel(int c){}public void close(){}};
        ImsSession.Listener listener=new ImsSession.Listener(){public void state(String s){}public void sms(String f,String t){}public void smsStatus(String i,String s){}public void call(ImsSession.Call c){calls.add(c);}public void audio(byte[] a,RtpEndpoint.Format f){audio.add(a);}};
        ImsSession ims=new ImsSession(new ImsSession.Config("10.8.0.2",5060,false,"+1234",false,""),net[0],new SimCard(card),new SimCard.Identity("234150123456789","89","234","15"),listener);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<?> registration=worker.submit(()->{try{ims.register();}catch(Exception e){throw new RuntimeException(e);}});PacketNetwork.Datagram d=signal.receive(1000);SipMessage reg=SipMessage.parse(d.data());signal.send(d.host(),d.port(),reply(reg,new byte[0]));registration.get(2,TimeUnit.SECONDS);
            ims.dial("+123456789");d=signal.receive(1000);SipMessage invite=SipMessage.parse(d.data());assertEquals("INVITE",invite.method());String sdp="v=0\r\no=carrier 1 1 IN IP4 10.8.0.2\r\ns=Call\r\nc=IN IP4 10.8.0.2\r\nt=0 0\r\nm=audio 31000 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\n";signal.send(d.host(),d.port(),reply(invite,sdp.getBytes(StandardCharsets.UTF_8)));assertEquals("ACK",SipMessage.parse(signal.receive(1000).data()).method());
            ImsSession.Call call=null;long deadline=System.currentTimeMillis()+2000;while(System.currentTimeMillis()<deadline){call=calls.poll(100,TimeUnit.MILLISECONDS);if(call!=null&&call.state().equals("active"))break;}assertNotNull(call);assertEquals("active",call.state());assertEquals("PCMA",call.codec());
            byte[] frame=RtpEndpoint.encodeG711(new short[160],true);ims.sendAudio(frame);PacketNetwork.Datagram rtp=media.receive(1000);assertEquals(8,rtp.data()[1]&127);assertArrayEquals(frame,cut(rtp.data(),12,160));
            media.send(rtp.host(),rtp.port(),cat(hex("800800010000000000000063"),frame));assertArrayEquals(frame,audio.poll(1,TimeUnit.SECONDS));
            ims.hangup();assertEquals("BYE",SipMessage.parse(signal.receive(1000).data()).method());assertNull(ims.call());
        }finally{ims.close();worker.shutdownNow();net[0].close();net[1].close();}
    }
    private static byte[] reply(SipMessage req,byte[] body){List<String> h=new ArrayList<>(List.of("Via: "+req.header("via"),"From: "+req.header("from"),"To: "+req.header("to")+";tag=carrier","Call-ID: "+req.header("call-id"),"CSeq: "+req.header("cseq"),"Contact: <sip:carrier@10.8.0.2:5060>"));if(body.length>0)h.add("Content-Type: application/sdp");return SipMessage.encode("SIP/2.0 200 OK",h,body);}
}
