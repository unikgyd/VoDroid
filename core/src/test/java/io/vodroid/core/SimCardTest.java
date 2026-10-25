package io.vodroid.core;

import org.junit.Test;
import java.util.*;
import static io.vodroid.core.Bytes.*;
import static org.junit.Assert.*;

public class SimCardTest {
    @Test public void manualMncWorksWhenEfAdIsUnavailable()throws Exception{
        ArrayDeque<byte[]> replies=new ArrayDeque<>(List.of(hex("9000"),hex("0819325400214365879000"),hex("9000"),hex("9000"),hex("981032547698103254769000")));
        SimCard.Transport transport=new SimCard.Transport(){
            public int open(byte[] aid){return 1;}
            public byte[] transmit(int channel,byte[] cmd){if(u(cmd[1])==0xa4&&cmd.length>=7&&u16(cmd,5)==0x6fad)throw new AssertionError("Manual MNC must not require EF AD");return replies.remove();}
            public void closeChannel(int channel){}public void close(){}
        };
        try(SimCard card=new SimCard(transport)){card.connect("A0000000871002");assertEquals("450",card.identity(3).mnc());assertTrue(replies.isEmpty());}
    }
    @Test public void identityReopensUsimThroughTransportForOmapi() throws Exception {
        List<Integer> closed=new ArrayList<>();List<byte[]> opened=new ArrayList<>();
        ArrayDeque<byte[]> replies=new ArrayDeque<>(List.of(hex("9000"),hex("0819325400214365879000"),hex("9000"),hex("000000029000"),hex("9000"),hex("9000"),hex("981032547698103254769000")));
        SimCard.Transport transport=new SimCard.Transport(){
            public int open(byte[] aid){opened.add(aid.clone());return opened.size();}
            public byte[] transmit(int channel,byte[] cmd){
                if(u(cmd[1])==0xa4&&u(cmd[2])==4)throw new SecurityException("SELECT by DF name command not allowed");
                assertEquals(1,channel);return replies.remove();
            }
            public void closeChannel(int channel){closed.add(channel);}
            public void close(){}
        };
        try(SimCard sim=new SimCard(transport)){
            sim.connect("A0000000871002");SimCard.Identity id=sim.identity(0);
            assertEquals("123450012345678",id.imsi());assertEquals("123",id.mcc());assertEquals("45",id.mnc());
            assertEquals("89012345678901234567",id.iccid());assertTrue(replies.isEmpty());
            assertEquals(2,opened.size());assertArrayEquals(opened.get(0),opened.get(1));assertEquals(List.of(1),closed);
        }
        assertEquals(List.of(1,2),closed);
    }
}
