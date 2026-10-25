package io.vodroid.core;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;

public class IkeSuiteTest {
    @Test public void hmacVectorsAndPrfPlus()throws Exception{
        byte[] key=new byte[20];java.util.Arrays.fill(key,(byte)0x0b);byte[] message="Hi There".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        IkeSuite sha1=new IkeSuite(128,2,2,2),sha256=IkeSuite.DEFAULT;
        assertArrayEquals(hex("B617318655057264E28BC0B6FB378C8EF146BE00"),sha1.prf(key,message));
        assertArrayEquals(hex("B0344C61D8DB38535CA8AFCEAF0BF12B881DC200C9833DA726E9376C2E32CFF7"),sha256.prf(key,message));
        byte[] first=sha1.prf(key,cat(message,b(1))),second=sha1.prf(key,cat(first,message,b(2)));
        assertArrayEquals(cat(first,second),sha1.expand(key,message,40));
        assertEquals(132,sha1.ikeKeyLength());assertEquals(72,sha1.childKeyLength());assertEquals(192,sha256.ikeKeyLength());
    }
    @Test public void selectedSuiteMustMatchOfferAndWireLengths()throws Exception{
        // One independently encoded AES-256 / SHA1 / MODP2 responder selection.
        byte[] selected=hex("0000002C010100040300000C0100000C800E0100030000080200000203000008030000020000000804000002");
        IkeSuite suite=IkeWire.select(selected,true,2).suite();assertEquals(new IkeSuite(256,2,2,2),suite);
        assertThrows(IOException.class,()->IkeWire.select(selected,true,14));
        byte[] unsupported=selected.clone();unsupported[27]=1;assertThrows(IOException.class,()->IkeWire.select(unsupported,true,2));
        byte[] missingPrf=selected.clone();missingPrf[27]=0;assertThrows(IOException.class,()->IkeWire.select(missingPrf,true,2));
        byte[] count=selected.clone();count[7]=3;assertThrows(IOException.class,()->IkeWire.select(count,true,2));
        byte[] continuation=selected.clone();continuation[36]=3;assertThrows(IOException.class,()->IkeWire.select(continuation,true,2));
        assertThrows(IOException.class,()->IkeWire.select(IkeWire.offer(true,new byte[0],14),true,14));
    }
    @Test public void allOfferedEspSuitesAuthenticateAndRejectTampering()throws Exception{
        for(int bits:new int[]{128,256})for(int integrity:new int[]{2,12}){
            IkeSuite suite=new IkeSuite(bits,0,integrity,0);byte[] keys=Crypto.random(suite.childKeyLength());int half=keys.length/2;
            byte[] reverse=cat(cut(keys,half,half),cut(keys,0,half)),left=hex("11223344"),right=hex("55667788");
            try(Esp sender=new Esp(left,right,keys,suite);Esp receiver=new Esp(right,left,reverse,suite)){
                byte[] inner=hex("4500001400010000401100000A0000010A000002"),packet=sender.encrypt(inner),broken=packet.clone();broken[broken.length-1]^=1;
                assertThrows(IOException.class,()->receiver.decrypt(broken));assertArrayEquals(inner,receiver.decrypt(packet));assertThrows(IOException.class,()->receiver.decrypt(packet));
            }
        }
    }
    @Test public void legacyRetryOnlyForExplicitNegotiationRejection(){
        assertTrue(IkeSession.legacyNegotiationRequested(List.of(IkeWire.p(41,IkeWire.notify(14,new byte[0])))));
        assertTrue(IkeSession.legacyNegotiationRequested(List.of(IkeWire.p(41,IkeWire.notify(17,n16(2))))));
        assertFalse(IkeSession.legacyNegotiationRequested(List.of(IkeWire.p(41,IkeWire.notify(17,n16(14))))));
        assertFalse(IkeSession.legacyNegotiationRequested(List.of(IkeWire.p(41,IkeWire.notify(24,new byte[0])))));
        assertFalse(IkeSession.legacyNegotiationRequested(List.of()));
    }
}
