package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.Bytes.*;
import java.util.*;
import java.util.concurrent.*;

public class NetworkTest {
    @Test public void tcpListenerReceivesCarrierInitiatedConnections() throws Exception {
        PacketNetwork[] peers=new PacketNetwork[2];peers[0]=new PacketNetwork("10.2.0.1",ip->peers[1].receive(ip));peers[1]=new PacketNetwork("10.2.0.2",ip->peers[0].receive(ip));
        try(PacketNetwork.TcpListener listener=peers[1].listenTcp(5060);TcpStream client=peers[0].connectTcp("10.2.0.2",5060,24000);TcpStream server=listener.accept(100)){
            client.write(hex("AABBCC"));assertArrayEquals(hex("AABBCC"),server.input.readNBytes(3));server.write(hex("DDEE"));assertArrayEquals(hex("DDEE"),client.input.readNBytes(2));
        }finally{peers[0].close();peers[1].close();}
    }
    @Test public void userspaceUdpFragmentsAndReassemblesWithoutVpn() throws Exception {
        List<byte[]> wire=new ArrayList<>();PacketNetwork a=new PacketNetwork("10.0.0.1",wire::add),b=new PacketNetwork("10.0.0.2",packet->{});
        try{PacketNetwork.Port source=a.openUdp(21000),target=b.openUdp(22000);byte[] text=Crypto.random(4000);source.send("10.0.0.2",22000,text);assertEquals(4,wire.size());Collections.reverse(wire);for(byte[] p:wire)b.receive(p);PacketNetwork.Datagram received=target.receive(1000);assertArrayEquals(text,received.data());assertEquals(21000,received.port());assertEquals("10.0.0.1",received.host());}finally{a.close();b.close();}
    }
    @Test public void invalidUdpChecksumIsDropped() throws Exception {List<byte[]> wire=new ArrayList<>();try(PacketNetwork a=new PacketNetwork("10.0.0.1",wire::add);PacketNetwork b=new PacketNetwork("10.0.0.2",p->{})){PacketNetwork.Port target=b.openUdp(22000);a.openUdp(21000).send("10.0.0.2",22000,hex("01020304"));byte[] p=wire.get(0);p[p.length-1]^=1;b.receive(p);assertThrows(java.net.SocketTimeoutException.class,()->target.receive(5));}}
    @Test public void innerImsEspIntegrityAndPortPolicy() throws Exception {
        List<byte[]> wire=new ArrayList<>();try(PacketNetwork a=new PacketNetwork("10.0.0.1",wire::add);PacketNetwork b=new PacketNetwork("10.0.0.2",packet->{})){
            ImsIpsec sa=new ImsIpsec(21000,21001),sb=new ImsIpsec(22000,22001);byte[] key=Crypto.random(16);sa.install(List.of(sb.offer()),key,"10.0.0.2");sb.install(List.of(sa.offer()),key,"10.0.0.1");a.security(sa);b.security(sb);PacketNetwork.Port receiver=b.openUdp(22001);a.openUdp(21000).send("10.0.0.2",22001,hex("AABBCCDD"));assertEquals(50,u(wire.get(0)[9]));b.receive(wire.get(0));assertArrayEquals(hex("AABBCCDD"),receiver.receive(100).data());assertThrows(java.io.IOException.class,()->b.receive(wire.get(0)));
        }
    }
    @Test public void tcpConnectWriteAndReadUseOwnedIpStack() throws Exception {
        PacketNetwork[] peers=new PacketNetwork[2];int remotePort=5060;long[] serverSeq={12345};List<byte[]> outgoing=new ArrayList<>();
        peers[0]=new PacketNetwork("10.1.0.1",ip->{outgoing.add(ip);int h=20;long seq=u32(ip,h+4),ack=u32(ip,h+8);int flags=u(ip[h+13]),header=(u(ip[h+12])>>>4)*4;int count=ip.length-h-header;byte[] data=hex("68656C6C6F");if((flags&2)!=0){byte[] syn=segment(u16(ip,h),serverSeq[0],seq+1,0x12,new byte[0]);peers[1].sendTransport(hex("0A010001"),6,syn);serverSeq[0]++;}else if(count>0){byte[] response=segment(u16(ip,h),serverSeq[0],seq+count,0x18,data);peers[1].sendTransport(hex("0A010001"),6,response);serverSeq[0]+=data.length;}});
        peers[1]=new PacketNetwork("10.1.0.2",peers[0]::receive);
        try(TcpStream tcp=peers[0].connectTcp("10.1.0.2",remotePort,23000)){tcp.write(hex("010203"));assertArrayEquals(hex("68656C6C6F"),tcp.input.readNBytes(5));assertTrue(outgoing.size()>=4);}finally{peers[0].close();peers[1].close();}
    }
    private static byte[] segment(int target,long seq,long ack,int flags,byte[] body){byte[] tcp=cat(n16(5060),n16(target),n32(seq),n32(ack),b(0x50,flags),n16(16000),new byte[4],body);int c=PacketNetwork.checksum(cat(hex("0A0100020A010001"),b(0,6),n16(tcp.length),tcp));tcp[16]=(byte)(c>>>8);tcp[17]=(byte)c;return tcp;}
}
