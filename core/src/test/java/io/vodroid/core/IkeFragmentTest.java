package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.IkeWire.*;
import static io.vodroid.core.Bytes.*;

public class IkeFragmentTest {
    private final byte[] enc=Crypto.random(16),auth=Crypto.random(32);
    private final Message header=new Message(hex("0102030405060708"),hex("1112131415161718"),35,0x20,3,0,new byte[0]);
    @Test public void outOfOrderDuplicateFragmentsReassembleOnlyWhenComplete()throws Exception{
        byte[] certificate=Crypto.random(6000);List<byte[]> packets=IkeEncrypted.seal(header,List.of(p(37,certificate),p(48,b(3,7,0,4))),IkeSuite.DEFAULT,enc,auth,true);
        assertTrue(packets.size()>2);for(byte[] packet:packets){assertTrue(packet.length<=1200);assertEquals(53,decode(packet).first());}
        try(IkeEncrypted.Reassembly assembler=new IkeEncrypted.Reassembly()){
            for(int n=packets.size()-1;n>0;n--)assertNull(assembler.accept(packets.get(n),IkeSuite.DEFAULT,enc,auth,true,0));
            assertNull(assembler.accept(packets.get(1),IkeSuite.DEFAULT,enc,auth,true,0));
            IkeEncrypted.Decoded decoded=assembler.accept(packets.get(0),IkeSuite.DEFAULT,enc,auth,true,0);
            assertArrayEquals(certificate,get(decoded.payloads(),37));assertArrayEquals(b(3,7,0,4),get(decoded.payloads(),48));
        }
    }
    @Test public void integrityAndNegotiationAreRequiredForEveryFragment()throws Exception{
        List<byte[]> packets=IkeEncrypted.seal(header,List.of(p(37,new byte[3000])),IkeSuite.DEFAULT,enc,auth,true);byte[] corrupt=packets.get(1).clone();corrupt[corrupt.length-1]^=1;
        assertThrows(IOException.class,()->IkeEncrypted.open(corrupt,IkeSuite.DEFAULT,enc,auth,true));assertThrows(IOException.class,()->IkeEncrypted.open(packets.get(0),IkeSuite.DEFAULT,enc,auth,false));
        try(IkeEncrypted.Reassembly assembler=new IkeEncrypted.Reassembly()){for(int n=0;n<packets.size()-1;n++)assertNull(assembler.accept(packets.get(n),IkeSuite.DEFAULT,enc,auth,true,0));}
    }
    @Test public void smallAndUnnegotiatedMessagesUseNormalSk()throws Exception{
        for(boolean negotiated:new boolean[]{false,true}){
            List<byte[]> packets=IkeEncrypted.seal(header,List.of(p(48,b(3,7,0,4))),IkeSuite.DEFAULT,enc,auth,negotiated);assertEquals(1,packets.size());assertEquals(46,decode(packets.get(0)).first());
            try(IkeEncrypted.Reassembly assembler=new IkeEncrypted.Reassembly()){assertArrayEquals(b(3,7,0,4),get(assembler.accept(packets.get(0),IkeSuite.DEFAULT,enc,auth,negotiated,0).payloads(),48));}
        }
    }
    @Test public void retransmissionWithSmallerMtuReplacesIncompleteAssembly()throws Exception{
        List<Payload> payloads=List.of(p(37,new byte[4000]));List<byte[]> first=IkeEncrypted.seal(header,payloads,IkeSuite.DEFAULT,enc,auth,true,1200),retry=IkeEncrypted.seal(header,payloads,IkeSuite.DEFAULT,enc,auth,true,700);
        try(IkeEncrypted.Reassembly assembler=new IkeEncrypted.Reassembly()){
            assertNull(assembler.accept(first.get(0),IkeSuite.DEFAULT,enc,auth,true,0));IkeEncrypted.Decoded result=null;
            for(byte[] packet:retry)result=assembler.accept(packet,IkeSuite.DEFAULT,enc,auth,true,1);
            assertNotNull(result);assertEquals(4000,get(result.payloads(),37).length);
        }
    }
    @Test public void oversizedUnfragmentedMessagesCannotWrapTheSkLength()throws Exception{
        assertThrows(IOException.class,()->IkeEncrypted.seal(header,List.of(p(37,new byte[40000]),p(37,new byte[30000])),IkeSuite.DEFAULT,enc,auth,false));
    }
}
