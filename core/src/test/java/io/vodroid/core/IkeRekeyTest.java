package io.vodroid.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;
import static io.vodroid.core.IkeWire.*;

public class IkeRekeyTest {
    private static final long SECOND=TimeUnit.SECONDS.toNanos(1);
    private static byte[] swapped(byte[] keys){return cat(cut(keys,keys.length/2,keys.length/2),cut(keys,0,keys.length/2));}
    private static final class Pair implements AutoCloseable {
        final ArrayDeque<byte[]> ab=new ArrayDeque<>(),ba=new ArrayDeque<>();
        final byte[] i=hex("0102030405060708"),r=hex("1112131415161718"),d=Crypto.random(32),ai=Crypto.random(32),ar=Crypto.random(32),ei=Crypto.random(16),er=Crypto.random(16);
        final IkeControlPlane a,b;long now=SECOND;
        Pair(){this(IkeTiming.DEFAULT);}
        Pair(IkeTiming timing){byte[] keys=Crypto.random(96),x=hex("12345678"),y=hex("87654321");IkeSuite esp=new IkeSuite(128,0,12,0);
            a=new IkeControlPlane(new IkeSa(i,r,IkeSuite.DEFAULT,true,d,ai,ar,ei,er,0),new EspChildren(new Esp(x,y,keys,esp),0),true,IkePolicy.AUTO,"10.0.0.1",ab::addAll,s->{},0,timing);
            b=new IkeControlPlane(new IkeSa(i,r,IkeSuite.DEFAULT,false,d,ai,ar,ei,er,0),new EspChildren(new Esp(y,x,swapped(keys),esp),0),true,IkePolicy.AUTO,"10.0.0.2",ba::addAll,s->{},0,timing);
        }
        void pump()throws Exception{for(int n=0;n<100;n++){if(ab.isEmpty()&&ba.isEmpty())return;while(!ab.isEmpty())b.receive(ab.remove(),now);while(!ba.isEmpty())a.receive(ba.remove(),now);}fail("Control exchange did not converge");}
        void data()throws Exception{byte[] ip=hex("4500001400000000401100000A0000010A000002");assertArrayEquals(ip,b.children.decrypt(a.children.encrypt(ip),now));assertArrayEquals(ip,a.children.decrypt(b.children.encrypt(ip),now));}
        public void close(){a.close();b.close();}
    }
    @Test public void childRekeysBothDirectionsKeepTrafficAndOldInboundWindow()throws Exception{
        try(Pair p=new Pair()){
            p.data();byte[] ip=hex("4500001400000000401100000A0000020A000001"),old=p.b.children.encrypt(ip),oldSpi=p.a.children.active().inboundSpi();
            assertTrue(p.a.rekey(false,p.now));p.b.receive(p.ab.remove(),p.now);p.a.receive(p.ba.remove(),p.now);
            assertFalse(Arrays.equals(oldSpi,p.a.children.active().inboundSpi()));assertArrayEquals(ip,p.a.children.decrypt(old,p.now));p.pump();p.data();
            assertTrue(p.b.rekey(false,++p.now));p.pump();p.data();assertEquals(2,p.a.rekeys());assertEquals(2,p.b.rekeys());
            assertThrows(IOException.class,()->p.a.children.decrypt(old,p.now+31*SECOND));
        }
    }
    @Test public void ikeRekeyCanReverseInitiatorRoleAndRestartsMessageCounters()throws Exception{
        try(Pair p=new Pair()){
            assertTrue(p.a.rekey(true,p.now));p.pump();p.data();
            assertTrue(p.b.rekey(true,++p.now));Message request=decode(p.ba.peek());assertEquals(0,request.flags()&8);assertEquals(0,request.id());p.pump();p.data();
            p.now+=31*SECOND;p.a.tick(p.now);Message dpd=decode(p.ab.peek());assertEquals(0,dpd.flags()&8);assertEquals(0,dpd.id());assertFalse(Arrays.equals(p.i,dpd.initiator()));p.pump();p.data();
            assertEquals(2,p.a.rekeys());assertEquals(2,p.b.rekeys());
        }
    }
    @Test public void lostRekeyReplyRetransmitsIdenticalRequestAndCachedResponse()throws Exception{
        for(boolean ike:new boolean[]{false,true})try(Pair p=new Pair()){
            p.a.rekey(ike,p.now);byte[] request=p.ab.remove();p.b.receive(request,p.now);byte[] firstReply=p.ba.remove();
            p.now+=5*SECOND;p.a.tick(p.now);assertArrayEquals(request,p.ab.peek());p.b.receive(p.ab.remove(),p.now);assertArrayEquals(firstReply,p.ba.peek());p.pump();p.data();assertEquals(1,p.a.rekeys());assertEquals(1,p.b.rekeys());
        }
    }
    @Test public void simultaneousRekeysUseTemporaryFailureAndRetryWithoutChangingKeys()throws Exception{
        try(Pair p=new Pair()){
            p.a.rekey(true,p.now);p.b.rekey(true,p.now);p.pump();assertEquals(0,p.a.rekeys());assertEquals(0,p.b.rekeys());p.data();
            p.now+=32*SECOND;assertTrue(p.a.rekey(true,p.now));p.pump();p.data();assertEquals(1,p.a.rekeys());
        }
    }
    @Test public void badMacWrongIdAndWrongRoleCannotCompleteProbe()throws Exception{
        try(Pair p=new Pair()){
            p.now=30*SECOND;p.a.tick(p.now);Message request=decode(p.ab.remove());
            byte[] valid=IkeEncrypted.seal(new Message(p.i,p.r,37,32,request.id(),0,b()),List.of(),IkeSuite.DEFAULT,p.er,p.ar,true).get(0),bad=valid.clone();bad[bad.length-1]^=1;
            p.a.receive(bad,p.now);p.a.receive(IkeEncrypted.seal(new Message(p.i,p.r,37,32,request.id()+1,0,b()),List.of(),IkeSuite.DEFAULT,p.er,p.ar,true).get(0),p.now);
            p.now+=5*SECOND;p.a.tick(p.now);assertEquals(1,p.ab.size());p.a.receive(valid,p.now);p.ab.clear();p.now+=5*SECOND;p.a.tick(p.now);assertTrue(p.ab.isEmpty());
        }
    }
    @Test public void duplicatePeerDpdGetsSameReplyAndFragmentedInformationalReassembles()throws Exception{
        try(Pair p=new Pair()){
            byte[] large=Crypto.random(5000);List<byte[]> fragments=IkeEncrypted.seal(new Message(p.i,p.r,37,0,0,0,b()),List.of(p(41,IkeWire.notify(40960,large))),IkeSuite.DEFAULT,p.er,p.ar,true);
            assertTrue(fragments.size()>2);for(int n=fragments.size()-1;n>0;n--)p.a.receive(fragments.get(n),p.now);assertTrue(p.ab.isEmpty());p.a.receive(fragments.get(0),p.now);byte[] reply=p.ab.remove();
            for(byte[] fragment:fragments)p.a.receive(fragment,p.now);assertArrayEquals(reply,p.ab.remove());
        }
    }
    @Test public void unresponsivePeerExpiresInsteadOfLeavingADeadRegisteredTunnel()throws Exception{
        try(Pair p=new Pair()){
            p.now=30*SECOND;p.a.tick(p.now);for(int n=0;n<3;n++){p.now+=5*SECOND;p.a.tick(p.now);}p.now+=5*SECOND;assertThrows(SocketTimeoutException.class,()->p.a.tick(p.now));
        }
    }
    @Test public void newIkeKeysUseOldPrfEvenWhenNewSuiteChangesPrf()throws Exception{
        IkeSuite old=new IkeSuite(128,2,2,2),next=IkeSuite.DEFAULT;byte[] key=hex("000102030405060708090A0B0C0D0E0F10111213"),dh=hex("AABBCCDD"),ni=new byte[32],nr=new byte[32],i=hex("0102030405060708"),r=hex("1112131415161718");Arrays.fill(nr,(byte)1);
        try(IkeSa initial=new IkeSa(i,r,old,true,key,new byte[20],new byte[20],new byte[16],new byte[16],0);IkeSa successor=IkeSa.rekey(initial,i,r,next,false,dh,ni,nr,1)){
            byte[] seed=Crypto.mac("HmacSHA1",key,cat(dh,ni,nr)),first=Crypto.mac("HmacSHA256",seed,cat(ni,nr,i,r,b(1)));assertArrayEquals(first,successor.d);assertFalse(successor.localInitiator);
        }
    }
    @Test public void unsupportedOrUnofferedRekeyAlgorithmsAreRejected()throws Exception{
        byte[] spi=hex("1122334455667788");IkeRekeyProposal.Choice choice=new IkeRekeyProposal.Choice(1,spi,IkeSuite.DEFAULT,14);byte[] sa=IkeRekeyProposal.encode(choice,true,spi);
        assertEquals(IkeSuite.DEFAULT,IkeRekeyProposal.selected(sa,true,14,IkeSuite.DEFAULT).suite());assertThrows(IOException.class,()->IkeRekeyProposal.selected(sa,true,2,new IkeSuite(128,2,2,2)));
        assertThrows(IllegalArgumentException.class,()->chain(List.of(p(37,new byte[65532]))));
    }
    @Test public void peerChildPfsRekeyUsesDhAndResponderKeyDirection()throws Exception{
        try(Pair p=new Pair();IkeDh peerDh=new IkeDh(14)){
            byte[] spi=hex("AABBCCDD"),ni=Crypto.random(32);IkeSuite suite=new IkeSuite(256,0,2,0);
            List<Payload> request=List.of(p(33,IkeRekeyProposal.encode(new IkeRekeyProposal.Choice(1,spi,suite,14),false,spi)),p(40,ni),p(34,peerDh.payload()),p(41,cat(b(3,4),n16(16393),p.a.children.active().outboundSpi())),p(44,trafficSelector()),p(45,trafficSelector()));
            p.a.receive(IkeEncrypted.seal(new Message(p.i,p.r,36,0,0,0,b()),request,IkeSuite.DEFAULT,p.er,p.ar,true).get(0),p.now);
            IkeEncrypted.Opened response=IkeEncrypted.open(p.ab.remove(),IkeSuite.DEFAULT,p.ei,p.ai,true);List<Payload> ps=parse(response.first(),response.plain());
            IkeRekeyProposal.Choice selected=IkeRekeyProposal.selected(get(ps,33),false,14,suite);byte[] keys=IkeSuite.DEFAULT.expand(p.d,cat(peerDh.shared(get(ps,34)),ni,get(ps,40)),suite.childKeyLength());
            try(Esp peer=new Esp(spi,selected.spi(),keys,suite)){
                byte[] ip=hex("4500001400000000401100000A0000010A000002");assertArrayEquals(ip,peer.decrypt(p.a.children.encrypt(ip)));assertArrayEquals(ip,p.a.children.decrypt(peer.encrypt(ip),p.now));
            }
        }
    }
    @Test public void activeChildDeleteAcknowledgesItsSpiThenClosesInsteadOfContinuing()throws Exception{
        try(Pair p=new Pair()){
            byte[] peerSpi=p.a.children.active().outboundSpi();byte[] packet=IkeEncrypted.seal(new Message(p.i,p.r,37,0,0,0,b()),List.of(p(42,cat(b(3,4,0,1),peerSpi))),IkeSuite.DEFAULT,p.er,p.ar,true).get(0);
            assertThrows(IOException.class,()->p.a.receive(packet,p.now));IkeEncrypted.Opened reply=IkeEncrypted.open(p.ab.remove(),IkeSuite.DEFAULT,p.ei,p.ai,true);
            assertArrayEquals(cat(b(3,4,0,1),p.a.children.active().inboundSpi()),get(parse(reply.first(),reply.plain()),42));
        }
    }
    @Test public void carrierSoftLifetimesTriggerChildPfsAndIkeRekeyBeforeHardExpiry()throws Exception{
        try(Pair p=new Pair(new IkeTiming(120,300,60,180,true))){
            p.now=60*SECOND;p.a.tick(p.now);Message child=decode(p.ab.peek());assertEquals(36,child.exchange());
            IkeEncrypted.Opened opened=IkeEncrypted.open(p.ab.peek(),IkeSuite.DEFAULT,p.ei,p.ai,true);assertNotNull(get(parse(opened.first(),opened.plain()),34));p.pump();p.data();assertEquals(1,p.a.rekeys());
            p.now=120*SECOND;p.a.tick(p.now);p.pump();p.data();assertEquals(2,p.a.rekeys());
        }
        assertEquals(IkeTiming.DEFAULT,IkeTiming.hints(0,0,Integer.MAX_VALUE,-1,false));
    }
}
