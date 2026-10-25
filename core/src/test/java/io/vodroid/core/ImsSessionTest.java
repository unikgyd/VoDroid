package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static io.vodroid.core.Bytes.*;

/** Carrier messages are exchanged entirely in memory, never with a live network. */
public class ImsSessionTest {
    @Test public void optionalImsIpsecAllowsAkaChallengeWithoutSecurityServer()throws Exception{registrationSecurity(false,"");}
    @Test public void requiredImsIpsecRejectsMissingAgreement()throws Exception{registrationSecurity(true,"");}
    @Test public void unsupportedPresentAgreementNeverFallsBackToPlainSip()throws Exception{registrationSecurity(false,"ipsec-3gpp;alg=unknown;ealg=null");}
    private void registrationSecurity(boolean required,String agreement)throws Exception{
        PacketNetwork[] network=new PacketNetwork[2];network[0]=new PacketNetwork("10.9.0.1",p->network[1].receive(p));network[1]=new PacketNetwork("10.9.0.2",p->network[0].receive(p));
        SimCard.Transport transport=new SimCard.Transport(){public int open(byte[] aid){return 1;}public byte[] transmit(int channel,byte[] command){assertEquals(0x88,u(command[1]));return cat(b(0xdb,4,1,2,3,4,16),new byte[16],b(16),new byte[16],hex("9000"));}public void closeChannel(int channel){}public void close(){}};
        ImsSession.Listener listener=new ImsSession.Listener(){public void state(String value){}public void sms(String from,String text){}public void smsStatus(String id,String value){}public void call(ImsSession.Call call){}public void audio(byte[] data,RtpEndpoint.Format format){}};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(SimCard card=new SimCard(transport);PacketNetwork.Port carrier=network[1].openUdp(5060)){
            card.connect("A0000000871002");
            try(ImsSession ims=new ImsSession(new ImsSession.Config("10.9.0.2",5060,false,"",required,""),network[0],card,new SimCard.Identity("515661234567890","8901","515","66"),listener)){
                Future<?> registration=worker.submit(()->{try{ims.register();}catch(Exception error){throw new RuntimeException(error);}});
                PacketNetwork.Datagram datagram=carrier.receive(1000);SipMessage request=SipMessage.parse(datagram.data());
                List<String> headers=new ArrayList<>(List.of("Via: "+request.header("via"),"From: "+request.header("from"),"To: "+request.header("to")+";tag=carrier","Call-ID: "+request.header("call-id"),"CSeq: "+request.header("cseq"),"WWW-Authenticate: Digest realm=\"ims.mnc066.mcc515.3gppnetwork.org\", nonce=\""+Base64.getEncoder().encodeToString(new byte[32])+"\", algorithm=AKAv1-MD5, qop=\"auth\""));
                if(!agreement.isEmpty())headers.add("Security-Server: "+agreement);
                carrier.send(datagram.host(),datagram.port(),SipMessage.encode("SIP/2.0 401 Unauthorized",headers,new byte[0]));
                if(required||!agreement.isEmpty()){
                    ExecutionException error=assertThrows(ExecutionException.class,()->registration.get(2,TimeUnit.SECONDS));
                    assertTrue(error.getCause().getCause() instanceof java.io.IOException);assertFalse(ims.registered());assertThrows(java.net.SocketTimeoutException.class,()->carrier.receive(30));
                }else{
                    datagram=carrier.receive(1000);SipMessage authenticated=SipMessage.parse(datagram.data());assertTrue(authenticated.header("authorization").contains("response=\""));assertFalse(authenticated.header("authorization").contains("response=\"\""));
                    carrier.send(datagram.host(),datagram.port(),response(authenticated,200));registration.get(2,TimeUnit.SECONDS);assertTrue(ims.registered());
                }
            }
        }finally{worker.shutdownNow();network[0].close();network[1].close();}
    }
    @Test public void registrationOutboundSmsAndInboundSmsUseRealSipTransactions() throws Exception {
        PacketNetwork[] net=new PacketNetwork[2];net[0]=new PacketNetwork("10.9.0.1",p->net[1].receive(p));net[1]=new PacketNetwork("10.9.0.2",p->net[0].receive(p));
        List<String> states=new CopyOnWriteArrayList<>();BlockingQueue<String> statuses=new LinkedBlockingQueue<>(),received=new LinkedBlockingQueue<>();PacketNetwork.Port carrier=net[1].openUdp(5060);
        SimCard.Transport transport=new SimCard.Transport(){public int open(byte[] a){return 1;}public byte[] transmit(int c,byte[] cmd){throw new AssertionError("This mock registration must not request AKA");}public void closeChannel(int c){}public void close(){}};
        ImsSession.Listener listener=new ImsSession.Listener(){public void state(String s){states.add(s);}public void sms(String f,String t){received.add(f+":"+t);}public void smsStatus(String id,String s){statuses.add(s);}public void call(ImsSession.Call c){}public void audio(byte[] data,RtpEndpoint.Format f){}};
        ImsSession ims=new ImsSession(new ImsSession.Config("10.9.0.2",5060,false,"+447785016005",false,""),net[0],new SimCard(transport),new SimCard.Identity("234150123456789","8901","234","15"),listener);
        ExecutorService tasks=Executors.newSingleThreadExecutor();try{
            Future<?> registered=tasks.submit(()->{try{ims.register();}catch(Exception e){throw new RuntimeException(e);}});
            PacketNetwork.Datagram register=carrier.receive(1000);SipMessage request=SipMessage.parse(register.data());assertEquals("REGISTER",request.method());carrier.send(register.host(),register.port(),response(request,200));registered.get(2,TimeUnit.SECONDS);assertTrue(ims.registered());
            Future<?> sent=tasks.submit(()->{try{ims.sendSms("id1","+123456789","你好");}catch(Exception e){throw new RuntimeException(e);}});PacketNetwork.Datagram outgoing=carrier.receive(1000);SipMessage mo=SipMessage.parse(outgoing.data());assertEquals("MESSAGE",mo.method());assertEquals(0,mo.body[0]&7);carrier.send(outgoing.host(),outgoing.port(),response(mo,202));sent.get(2,TimeUnit.SECONDS);
            int reference=mo.body[1]&255;String contact=SipMessage.uri(request.header("contact"));int serverPort=Integer.parseInt(contact.substring(contact.indexOf(':',4)+1).split(";",2)[0]);
            byte[] ack=SipMessage.encode("MESSAGE "+contact+" SIP/2.0",headers("network-ack",1,serverPort),b(3,reference,0));carrier.send("10.9.0.1",serverPort,ack);assertEquals(200,SipMessage.parse(carrier.receive(1000).data()).code());
            long deadline=System.currentTimeMillis()+2000;String status="";while(System.currentTimeMillis()<deadline&&!status.startsWith("网络已确认")){String v=statuses.poll(200,TimeUnit.MILLISECONDS);if(v!=null)status=v;}assertTrue(status.startsWith("网络已确认"));
            byte[] tp=cat(b(0,4,0x91),hex("2143"),b(0,8),new byte[7],b(4),"收到".getBytes(StandardCharsets.UTF_16BE));byte[] mt=cat(b(1,20,0,0,tp.length),tp);carrier.send("10.9.0.1",serverPort,SipMessage.encode("MESSAGE "+contact+" SIP/2.0",headers("incoming-sms",2,serverPort),mt));assertEquals("+1234:收到",received.poll(2,TimeUnit.SECONDS));
            PacketNetwork.Datagram response=carrier.receive(1000);assertEquals("SIP response must originate from the addressed server port",serverPort,response.port());assertEquals(200,SipMessage.parse(response.data()).code());
            PacketNetwork.Datagram rpAck=carrier.receive(1000);SipMessage rp=SipMessage.parse(rpAck.data());assertEquals(2,rp.body[0]&7);carrier.send(rpAck.host(),rpAck.port(),response(rp,200));
        }finally{ims.close();tasks.shutdownNow();net[0].close();net[1].close();}
    }
    private static List<String> headers(String id,int seq,int port){return List.of("Via: SIP/2.0/UDP 10.9.0.2:5060;branch=z9hG4bK"+id,"From: <sip:carrier@ims.example>;tag=a","To: <sip:subscriber@10.9.0.1:"+port+">","Call-ID: "+id,"CSeq: "+seq+" MESSAGE","Content-Type: application/vnd.3gpp.sms");}
    private static byte[] response(SipMessage m,int code){return SipMessage.encode("SIP/2.0 "+code+" OK",List.of("Via: "+m.header("via"),"From: "+m.header("from"),"To: "+m.header("to")+";tag=carrier","Call-ID: "+m.header("call-id"),"CSeq: "+m.header("cseq")),new byte[0]);}
}
