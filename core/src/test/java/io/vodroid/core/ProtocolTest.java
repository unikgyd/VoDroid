package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;
import java.io.*;
import java.util.*;

public class ProtocolTest {
    @Test public void fips186PrfMatchesRfc4186AppendixA() {
        byte[] keys=Crypto.akaPrf(hex("e576d5ca332e9930018bf1baee2763c795b3c712"));
        assertArrayEquals(hex("536e5ebc4465582aa6a8ec9986ebb62025af1942efcbf4bc72b3943421f2a974"),cut(keys,0,32));
        assertArrayEquals(hex("39d45aeaf4e30601983e972b6cfd46d1c363773365690d09cd44976b525f47d3a60a985e955c53b090b2e4b73719196a402542968fd14a888f46b9a7886e4488"),cut(keys,32,64));
    }
    @Test public void aesCbcMatchesNistVector() throws Exception {
        byte[] key=hex("2b7e151628aed2a6abf7158809cf4f3c"),iv=hex("000102030405060708090a0b0c0d0e0f"),plain=hex("6bc1bee22e409f96e93d7e117393172a");
        byte[] encrypted=Crypto.aes(true,key,iv,plain);assertArrayEquals(hex("7649abac8119b246cee98e9b12e9197d"),encrypted);assertArrayEquals(plain,Crypto.aes(false,key,iv,encrypted));
    }
    @Test public void simRejectsMalformedVectorsAndWipesSecrets() throws Exception {
        assertThrows(IOException.class,()->SimCard.parseAka(hex("DB10AABB")));
        byte[] vector=cat(b(0xdb,4,1,2,3,4,16),new byte[16],b(16),new byte[16]);
        SimCard.Aka result=SimCard.parseAka(vector);assertTrue(result.synchronizedOk());assertEquals(4,result.res.length);result.close();assertArrayEquals(new byte[4],result.res);
        try(SimCard.Aka sync=SimCard.parseAka(cat(b(0xdc,14),new byte[14]))){assertFalse(sync.synchronizedOk());}
    }
    @Test public void simFollowsGetResponseAndCorrectLength() throws Exception {
        List<byte[]> commands=new ArrayList<>();ArrayDeque<byte[]> replies=new ArrayDeque<>(List.of(hex("6C02"),hex("AABB6102"),hex("CCDD9000")));
        SimCard.Transport t=new SimCard.Transport(){public int open(byte[] a){return 1;}public byte[] transmit(int c,byte[] cmd){commands.add(cmd);return replies.remove();}public void closeChannel(int c){}public void close(){}};
        try(SimCard sim=new SimCard(t)){sim.connect("A0000000871002");assertArrayEquals(hex("AABBCCDD"),sim.command(hex("00B0000001")));assertEquals(2,u(commands.get(1)[4]));assertArrayEquals(hex("00C0000002"),commands.get(2));}
    }
    @Test public void mncUsesExplicitLengthAndPadding() {
        SimCard.Identity two=new SimCard.Identity("234150123456789","8901","234","15");assertEquals("epdg.epc.mnc015.mcc234.pub.3gppnetwork.org",two.epdg());assertEquals("0234150123456789@wlan.mnc015.mcc234.3gppnetwork.org",two.nai());
        assertThrows(IllegalArgumentException.class,()->new SimCard.Identity("123","x","234","15"));
    }
    @Test public void ikePayloadsRoundTripAndRejectBadLength() throws Exception {
        List<IkeWire.Payload> ps=List.of(IkeWire.p(40,new byte[32]),IkeWire.p(41,IkeWire.notify(16417,new byte[0])));byte[] raw=IkeWire.encode(new byte[8],new byte[8],34,8,0,ps);IkeWire.Message message=IkeWire.decode(raw);List<IkeWire.Payload> parsed=IkeWire.parse(message.first(),message.body());assertEquals(2,parsed.size());assertArrayEquals(new byte[32],parsed.get(0).body());raw[27]--;assertThrows(IOException.class,()->IkeWire.decode(raw));
        assertThrows(IOException.class,()->IkeWire.parse(40,hex("00000003")));
    }
    @Test public void ikeProposalRejectsAlgorithmDowngrade() throws Exception {byte[] sa=IkeWire.proposal(true,new byte[0]);IkeWire.selectedSpi(sa,true);sa[19]=15;assertThrows(IOException.class,()->IkeWire.selectedSpi(sa,true));}
    @Test public void espRoundTripTamperAndReplay() throws Exception {
        byte[] keys=Crypto.random(96),reverse=cat(cut(keys,48,48),cut(keys,0,48)),left=hex("11223344"),right=hex("55667788");
        try(Esp a=new Esp(left,right,keys);Esp b=new Esp(right,left,reverse)){byte[] inner=hex("4500001400010000401100000A0000010A000002");byte[] packet=a.encrypt(inner);byte[] broken=packet.clone();broken[28]^=1;assertThrows(IOException.class,()->b.decrypt(broken));assertArrayEquals(inner,b.decrypt(packet));assertThrows(IOException.class,()->b.decrypt(packet));}
    }
    @Test public void sipParserHandlesCompactHeadersAndBinaryBody() throws Exception {byte[] raw=SipMessage.encode("MESSAGE sip:x@y SIP/2.0",List.of("v: SIP/2.0/UDP 10.0.0.1","i: call","CSeq: 1 MESSAGE","c: application/vnd.3gpp.sms"),hex("000102FF"));SipMessage m=SipMessage.parse(raw);assertEquals("call",m.header("call-id"));assertEquals("application/vnd.3gpp.sms",m.header("content-type"));assertArrayEquals(hex("000102FF"),m.body);assertThrows(IOException.class,()->SipMessage.parse(cat(raw,b(0))));assertThrows(IllegalArgumentException.class,()->SipMessage.encode("OPTIONS sip:a SIP/2.0",List.of("From: x\r\nInjected: true"),new byte[0]));}
    @Test public void sipDigestParametersRetainQuotedCommas() {Map<String,String> p=SipMessage.parameters("Digest realm=\"ims.example\", qop=\"auth,auth-int\", nonce=\"abc=\", algorithm=AKAv1-MD5");assertEquals("auth,auth-int",p.get("qop"));assertEquals("AKAv1-MD5",p.get("algorithm"));}
    @Test public void tcpSipFramingReadsConcatenatedMessages() throws Exception {byte[] one=SipMessage.encode("SIP/2.0 200 OK",List.of("Call-ID: first"),b(1,2));byte[] two=SipMessage.encode("SIP/2.0 401 Unauthorized",List.of("Call-ID: second"),new byte[0]);InputStream stream=new ByteArrayInputStream(cat(one,two));assertEquals(200,ImsSession.readTcpMessage(stream).code());assertEquals(401,ImsSession.readTcpMessage(stream).code());}
    @Test public void smsSubmitSegmentsAtUcs2Boundary() throws Exception {List<SmsCodec.Part> parts=SmsCodec.submit("+1234567890","中".repeat(71),"+447785016005",255);assertEquals(2,parts.size());assertEquals(255,parts.get(0).reference());assertEquals(0,parts.get(1).reference());for(SmsCodec.Part part:parts)assertTrue(part.rp().length<180);assertThrows(IOException.class,()->SmsCodec.submit("+123","😀","+456",1));}
    @Test public void inboundUcs2AndGsm7Decode() throws Exception {
        byte[] tp=cat(b(0,4,0x91),hex("2143"),b(0,8),new byte[7],b(4),"你好".getBytes(java.nio.charset.StandardCharsets.UTF_16BE));SmsCodec.Incoming sms=SmsCodec.deliver(cat(b(1,9,0,0,tp.length),tp));assertEquals("+1234",sms.sender());assertEquals("你好",sms.text());assertEquals("hello",SmsCodec.gsm7(hex("E8329BFD06"),5,0));
    }
    @Test public void concatenatedMessagesReassembleOutOfOrder() {SmsCodec.Assembler assembler=new SmsCodec.Assembler();assertNull(assembler.accept(new SmsCodec.Incoming(1,"+123","后",20,2,2)));assertEquals("前后",assembler.accept(new SmsCodec.Incoming(2,"+123","前",20,1,2)));}
    @Test public void g711ReferenceSilenceAndRoundTrip() {assertEquals(0,RtpEndpoint.decodeG711(hex("FF"),false)[0]);assertEquals(8,RtpEndpoint.decodeG711(hex("D5"),true)[0]);short[] samples={-20000,-1000,1000,20000};for(boolean alaw:new boolean[]{true,false}){short[] out=RtpEndpoint.decodeG711(RtpEndpoint.encodeG711(samples,alaw),alaw);for(int i=0;i<samples.length;i++)assertTrue(Math.abs(samples[i]-out[i])<1000);}}
}
