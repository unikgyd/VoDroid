package io.vodroid.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;

public class EapPrimeTest {
    private static final String ID="0555444333222111";
    private final byte[] ck=hex("5349fbe098649f948f5d2e973a81c00f"),ik=hex("9744871ad32bf9bbd1dd5ce54e3e2e5a"),autn=hex("bb52e91c747ac3ab2a5c23d15ee351d5"),rand=hex("81e92b6c0ee0e12ebceba8d92a99dfa5");
    private final byte[] kaut=hex("0842ea722ff6835bfa2032499fc3ec23c2f0e388b4f07543ffc677f1696d71ea");
    private final byte[] msk=hex("67c42d9aa56c1b79e295e3459fc3d187d42be0bf818d3070e362c5e967a4d544e8ecfe19358ab3039aff03b7c930588c055babee58a02650b067ec4e9347c75a");
    private int calls;
    private SimCard card(boolean sync)throws Exception{
        SimCard sim=new SimCard(new SimCard.Transport(){public int open(byte[] aid){return 1;}public byte[] transmit(int channel,byte[] command){calls++;assertEquals(0x88,u(command[1]));return sync?cat(b(0xdc,14),new byte[14],hex("9000")):cat(b(0xdb,8),hex("28d7b0f2a2ec3de5"),b(16),ck,b(16),ik,hex("9000"));}public void closeChannel(int channel){}public void close(){}});sim.connect("A0000000871002");return sim;
    }
    private static byte[] attr(int type,byte[] data){int length=(data.length+5)/4*4;byte[] result=new byte[length];result[0]=(byte)type;result[1]=(byte)(length/4);System.arraycopy(data,0,result,2,data.length);return result;}
    private byte[] challenge(int id,byte[] network,byte[] token,int... kdfs)throws Exception{
        byte[] attrs=cat(attr(1,cat(b(0,0),rand)),attr(2,cat(b(0,0),token)),attr(23,cat(n16(network.length),network)));
        for(int kdf:kdfs)attrs=cat(attrs,attr(24,n16(kdf)));attrs=cat(attrs,attr(11,new byte[18]));
        byte[] result=cat(b(1,id),n16(8+attrs.length),b(50,1,0,0),attrs);byte[] mac=Crypto.mac("HmacSHA256",kaut,result);System.arraycopy(mac,0,result,result.length-16,16);return result;
    }
    @Test public void rfc5448CaseOneMatchesCkIkAndFullKeyHierarchy()throws Exception{
        byte[] network="WLAN".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(hex("0093962d0dd84aa5684b045c9edffa04ccfc230ca74fcc96c0a5d61164f5a76c"),Crypto.akaPrimeBase(ck,ik,autn,network));
        byte[] keys=Crypto.akaPrimeKeys(ck,ik,autn,network,ID.getBytes(StandardCharsets.UTF_8));assertArrayEquals(kaut,cut(keys,16,32));assertArrayEquals(msk,cut(keys,80,64));
        assertArrayEquals(hex("766fa0a6c317174b812d52fbcd11a179"),cut(keys,0,16));
    }
    @Test public void primeChallengeProducesResAuthenticatedResponseAndMsk()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            byte[] request=challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1),response=eap.respond(request);
            assertEquals(50,u(response[4]));assertEquals("AKA′",eap.method());assertArrayEquals(msk,eap.msk());
            byte[] mac=cut(response,response.length-16,16),copy=response.clone();Arrays.fill(copy,copy.length-16,copy.length,(byte)0);assertArrayEquals(cut(Crypto.mac("HmacSHA256",kaut,copy),0,16),mac);
            assertArrayEquals(response,eap.respond(request));assertEquals(1,calls);assertNull(eap.respond(hex("03070004")));
        }
    }
    @Test public void kdfNegotiationCannotSilentlyChangeItsOfferedList()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            byte[] response=eap.respond(challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,9,1));assertArrayEquals(hex("18010001"),cut(response,8,response.length-8));assertEquals(0,calls);
            eap.respond(challenge(8,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1,9,1));assertArrayEquals(msk,eap.msk());assertEquals(1,calls);
        }
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){eap.respond(challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,9,1));assertThrows(IOException.class,()->eap.respond(challenge(8,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1)));}
    }
    @Test public void emptyNetworkMissingAmfAndChangedNetworkCannotAuthenticate()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            assertThrows(IOException.class,()->eap.respond(challenge(7,new byte[0],autn,1)));assertEquals(0,calls);
            byte[] wrong=autn.clone();wrong[6]&=0x7f;assertThrows(IOException.class,()->eap.respond(challenge(8,"WLAN".getBytes(StandardCharsets.UTF_8),wrong,1)));assertEquals(0,calls);
            assertThrows(IOException.class,()->eap.respond(challenge(9,"HRPD".getBytes(StandardCharsets.UTF_8),autn,1)));assertThrows(IllegalStateException.class,eap::msk);
        }
    }
    @Test public void synchronizationFailurePreservesKdfAndPinnedPrimeNeverUsesAka()throws Exception{
        try(SimCard card=card(true);EapAka eap=new EapAka(card,ID)){
            byte[] response=eap.respond(challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1));assertEquals(4,u(response[5]));assertArrayEquals(hex("18010001"),cut(response,response.length-4,4));assertThrows(IllegalStateException.class,eap::msk);
        }
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID,EapPolicy.AKA_PRIME)){
            int before=calls;byte[] response=eap.respond(hex("0107000817050000"));assertEquals(3,u(response[4]));assertEquals(50,u(response[5]));assertEquals(before,calls);
        }
    }
    @Test public void primeIdentityAndCheckcodeBindTheActualIdentityExchange()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            byte[] identityRequest=hex("0101000C320500000A010000"),response=eap.respond(identityRequest);
            byte[] actual=("6"+ID.substring(1)).getBytes(StandardCharsets.UTF_8);assertArrayEquals(actual,cut(response,12,actual.length));
            byte[] request=challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1),check=Crypto.digest("SHA-256",cat(identityRequest,response));
            request=cat(cut(request,0,request.length-20),attr(134,cat(b(0,0),check)),attr(11,new byte[18]));request[2]=(byte)(request.length>>>8);request[3]=(byte)request.length;
            byte[] keys=Crypto.akaPrimeKeys(ck,ik,autn,"WLAN".getBytes(StandardCharsets.UTF_8),actual),mac=Crypto.mac("HmacSHA256",cut(keys,16,32),request);System.arraycopy(mac,0,request,request.length-16,16);
            byte[] answer=eap.respond(request);assertArrayEquals(cut(keys,80,64),eap.msk());assertTrue(answer.length>60);
        }
    }
    @Test public void unknownKdfNeverAuthenticatesAndPrimeFailureClearsKeys()throws Exception{
        try(SimCard card=card(false);EapAka eap=new EapAka(card,ID)){
            assertThrows(IOException.class,()->eap.respond(challenge(7,"WLAN".getBytes(StandardCharsets.UTF_8),autn,9)));assertEquals(0,calls);
            eap.respond(challenge(8,"WLAN".getBytes(StandardCharsets.UTF_8),autn,1));assertEquals(1,calls);
            assertThrows(IOException.class,()->eap.respond(hex("04080004")));assertThrows(IllegalStateException.class,eap::msk);
        }
    }
}
