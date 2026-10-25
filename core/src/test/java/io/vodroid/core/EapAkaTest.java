package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EapAkaTest {
    private static final String ID="0234150123456789@wlan.mnc015.mcc234.3gppnetwork.org";
    private final byte[] ck=hex("000102030405060708090A0B0C0D0E0F"),ik=hex("101112131415161718191A1B1C1D1E1F");
    private int authentications;
    private SimCard card(boolean sync) throws Exception {SimCard.Transport t=new SimCard.Transport(){public int open(byte[] aid){return 1;}public byte[] transmit(int channel,byte[] command){authentications++;if(sync)return cat(b(0xdc,14),new byte[14],hex("9000"));assertEquals(0x88,u(command[1]));assertEquals(16,u(command[5]));assertEquals(16,u(command[22]));return cat(b(0xdb,4,0xa1,0xa2,0xa3,0xa4,16),ck,b(16),ik,hex("9000"));}public void closeChannel(int c){}public void close(){}};SimCard sim=new SimCard(t);sim.connect("A0000000871002");return sim;}
    private byte[] kaut() throws Exception{return cut(Crypto.akaPrf(Crypto.digest("SHA-1",cat(ID.getBytes(StandardCharsets.UTF_8),ik,ck))),16,16);}
    private byte[] signed(byte[] packet) throws Exception{byte[] result=packet.clone();byte[] mac=Crypto.mac("HmacSHA1",kaut(),result);System.arraycopy(mac,0,result,result.length-16,16);return result;}
    private byte[] challenge(boolean resultInd) throws Exception{byte[] attrs=cat(b(1,5,0,0),new byte[16],b(2,5,0,0),new byte[16],resultInd?b(135,1,0,0):new byte[0],b(11,5,0,0),new byte[16]);return signed(cat(b(1,7),n16(8+attrs.length),b(23,1,0,0),attrs));}
    @Test public void challengeAuthenticatesAndGeneratesResAndMsk() throws Exception{try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){byte[] response=eap.respond(challenge(false));assertEquals(2,u(response[0]));assertEquals(7,u(response[1]));assertEquals(3,u(response[8]));assertEquals(32,u16(response,10));assertArrayEquals(hex("A1A2A3A4"),cut(response,12,4));assertEquals(64,eap.msk().length);assertNull(eap.respond(hex("03070004")));}}
    @Test public void badNetworkMacCannotAuthenticate() throws Exception{try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){byte[] request=challenge(false);request[request.length-1]^=1;assertThrows(IOException.class,()->eap.respond(request));assertThrows(IOException.class,()->eap.respond(hex("03070004")));}}
    @Test public void autsIsReturnedWithoutInventingKeys() throws Exception{try(SimCard card=card(true);EapAka eap=new EapAka(card,ID)){byte[] answer=eap.respond(challenge(false));assertEquals(4,u(answer[5]));assertEquals(4,u(answer[8]));assertEquals(4,u(answer[9]));assertThrows(IllegalStateException.class,eap::msk);}}
    @Test public void protectedResultIndicationCannotBeSkipped() throws Exception{try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){eap.respond(challenge(true));assertThrows(IOException.class,()->eap.respond(hex("03070004")));byte[] notification=signed(cat(hex("01080020170C00000C0180000B050000"),new byte[16]));eap.respond(notification);assertNull(eap.respond(hex("03080004")));}}
    @Test public void duplicateChallengeUsesCachedResponseWithoutAnotherApdu()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            byte[] challenge=challenge(false),first=eap.respond(challenge);assertArrayEquals(first,eap.respond(challenge));assertEquals(1,authentications);
            challenge[10]^=1;assertThrows(IOException.class,()->eap.respond(challenge));assertEquals(1,authentications);
        }
    }
    @Test public void successMustMatchLastResponseAndNewChallengeInvalidatesOldKeys()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            eap.respond(challenge(false));assertThrows(IOException.class,()->eap.respond(hex("03080004")));assertThrows(IOException.class,()->eap.respond(hex("0307000500")));
            byte[] next=challenge(false);next[1]=8;assertThrows(IOException.class,()->eap.respond(next));assertThrows(IllegalStateException.class,eap::msk);
        }
    }
    @Test public void malformedMacAttributeIsRejectedBeforeCardAuthentication()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            byte[] malformed=challenge(false);malformed[malformed.length-19]=4;
            assertThrows(IOException.class,()->eap.respond(malformed));assertEquals(0,authentications);
        }
    }
    @Test public void authenticatedBiddingFlagPreventsAutoDowngradeButExplicitAkaIsAllowed()throws Exception{
        byte[] base=challenge(false);byte[] request=cat(cut(base,0,base.length-20),hex("88018000"),hex("0B050000"),new byte[16]);request[2]=(byte)(request.length>>>8);request[3]=(byte)request.length;request=signed(request);final byte[] wire=request;
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){assertThrows(IOException.class,()->eap.respond(wire));assertThrows(IllegalStateException.class,eap::msk);}
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID,EapPolicy.AKA)){eap.respond(wire);assertEquals(64,eap.msk().length);}
    }
    @Test public void methodNakOrNewIdentityCannotReusePreviousChallengeKeys()throws Exception{
        for(byte[] request:new byte[][]{hex("0108000519"),hex("0108000501"),hex("0108000817050000")})try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            eap.respond(challenge(false));eap.respond(request);assertThrows(IllegalStateException.class,eap::msk);assertThrows(IOException.class,()->eap.respond(hex("03080004")));
        }
    }
}
