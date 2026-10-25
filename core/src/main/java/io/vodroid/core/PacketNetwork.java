package io.vodroid.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.vodroid.core.Bytes.*;

/** Application-owned IPv4/UDP stack. No TUN, VPN slot, raw socket, or root required. */
public final class PacketNetwork implements AutoCloseable {
    public interface Sender {void send(byte[] ip) throws Exception;}
    public record Datagram(String host,int port,byte[] data){}
    public final class Port implements AutoCloseable {
        public final int number;
        private final BlockingQueue<Datagram> queue=new ArrayBlockingQueue<>(128);
        private volatile boolean closed;
        private final DatagramSocket systemSocket;
        private Port(int n) throws IOException {number=n;systemSocket=systemSockets?new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(local),n)):null;}
        public void send(String host,int port,byte[] data) throws Exception {if(closed)throw new IOException("UDP port closed");sendUdp(number,host,port,data);}
        public Datagram receive(long timeout) throws Exception {if(closed)throw new IOException("UDP port closed");if(systemSocket!=null){systemSocket.setSoTimeout((int)timeout);byte[] b=new byte[65535];DatagramPacket p=new DatagramPacket(b,b.length);systemSocket.receive(p);return new Datagram(p.getAddress().getHostAddress(),p.getPort(),cut(b,0,p.getLength()));}Datagram d=queue.poll(timeout,TimeUnit.MILLISECONDS);if(d==null)throw new SocketTimeoutException("UDP timeout");return d;}
        public void close(){closed=true;if(systemSocket!=null)systemSocket.close();ports.remove(number,this);}
    }
    public final class TcpListener implements AutoCloseable {
        private final int number;private final ServerSocket system;private final BlockingQueue<TcpStream> queue=new ArrayBlockingQueue<>(8);private volatile boolean closed;
        private TcpListener(int n) throws Exception{number=n;system=systemSockets?new ServerSocket(n,4,InetAddress.getByAddress(local)):null;}
        public TcpStream accept(long timeout) throws Exception {if(closed)throw new IOException("TCP listener closed");if(system!=null){system.setSoTimeout((int)timeout);return new TcpStream(system.accept());}TcpStream result=queue.poll(timeout,TimeUnit.MILLISECONDS);if(result==null)throw new SocketTimeoutException("TCP accept timeout");return result;}
        public void close(){closed=true;listeners.remove(number,this);if(system!=null)try{system.close();}catch(IOException ignored){}}
    }
    private record FragmentKey(String source,int id,int proto){}
    private static final class Fragments {final long created=System.currentTimeMillis();final TreeMap<Integer,byte[]> pieces=new TreeMap<>();int total=-1;byte[] header;}
    private final Map<Integer,Port> ports=new ConcurrentHashMap<>();
    private final Map<FragmentKey,Fragments> fragments=new HashMap<>();
    private final AtomicInteger nextPort=new AtomicInteger(20000+Crypto.RANDOM.nextInt(20000)),packetId=new AtomicInteger(Crypto.RANDOM.nextInt(65536));
    private final byte[] local;
    private final Sender sender;
    private final boolean systemSockets;
    private volatile ImsIpsec security;
    private volatile boolean closed;
    private final Map<Integer,TcpStream> tcp=new ConcurrentHashMap<>();
    private final Map<Integer,TcpListener> listeners=new ConcurrentHashMap<>();
    public PacketNetwork(String address,Sender sender) throws Exception{this(address,sender,false);}
    public PacketNetwork(String address,Sender sender,boolean systemSockets) throws Exception{local=InetAddress.getByName(address).getAddress();if(local.length!=4)throw new IOException("IPv4 required");this.sender=sender;this.systemSockets=systemSockets;}
    public byte[] transformOutbound(byte[] ip) throws Exception {ImsIpsec s=security;return s==null?ip:s.outbound(ip);}
    public byte[] transformInbound(byte[] ip) throws Exception {ImsIpsec s=security;return s==null?ip:s.inbound(ip);}
    public String address() throws Exception{return InetAddress.getByAddress(local).getHostAddress();}
    public void security(ImsIpsec s){security=s;}
    public TcpListener listenTcp(int port) throws Exception{TcpListener listener=new TcpListener(port);if(listeners.putIfAbsent(port,listener)!=null){listener.close();throw new IOException("TCP listener busy");}return listener;}
    public Port openUdp(int number) throws IOException{if(closed)throw new IOException("Network closed");if(number==0)number=freePort();Port p=new Port(number);if(ports.putIfAbsent(number,p)!=null)throw new IOException("UDP port already open");return p;}
    private int freePort(){for(int i=0;i<20000;i++){int p=nextPort.getAndUpdate(v->v>=60000?20000:v+1);if(!ports.containsKey(p)&&!tcp.containsKey(p))return p;}throw new IllegalStateException("No ports available");}
    public TcpStream connectTcp(String host,int port,int localPort) throws Exception{if(localPort==0)localPort=freePort();if(systemSockets){Socket socket=new Socket();try{socket.bind(new InetSocketAddress(InetAddress.getByAddress(local),localPort));socket.connect(new InetSocketAddress(host,port),12000);return new TcpStream(socket);}catch(Exception e){socket.close();throw e;}}TcpStream stream=new TcpStream(this,localPort,host,port);if(tcp.putIfAbsent(localPort,stream)!=null)throw new IOException("TCP port busy");try{stream.connect();return stream;}catch(Exception e){stream.close();throw e;}}
    public void sendUdp(int from,String host,int port,byte[] body) throws Exception{
        if(systemSockets){Port source=ports.get(from);if(source==null||source.systemSocket==null)throw new IOException("UDP port closed");source.systemSocket.send(new DatagramPacket(body,body.length,InetAddress.getByName(host),port));return;}
        if(body.length>65507)throw new IOException("UDP payload too large");byte[] target=InetAddress.getByName(host).getAddress();byte[] udp=cat(n16(from),n16(port),n16(body.length+8),new byte[2],body);int checksum=checksum(cat(local,target,b(0,17),n16(udp.length),udp));if(checksum==0)checksum=65535;udp[6]=(byte)(checksum>>>8);udp[7]=(byte)checksum;sendTransport(target,17,udp);
    }
    void sendTransport(byte[] target,int proto,byte[] transport) throws Exception{
        if(closed)throw new IOException("Network closed");byte[] ip=cat(b(0x45,0),n16(20+transport.length),n16(packetId.incrementAndGet()),b(0,0,64,proto,0,0),local,target,transport);fixHeader(ip);ImsIpsec s=security;if(s!=null)ip=s.outbound(ip);
        // Fragment after inner IMS ESP transformation; the receiver reassembles before verifying ESP.
        if(ip.length<=1280){sender.send(ip);return;}int header=(ip[0]&15)*4;byte[] payload=cut(ip,header,ip.length-header);for(int p=0;p<payload.length;p+=1256){int n=Math.min(1256,payload.length-p);byte[] fragment=cat(cut(ip,0,header),cut(payload,p,n));int flags=(p+n<payload.length?0x2000:0)|(p/8);fragment[6]=(byte)(flags>>>8);fragment[7]=(byte)flags;fragment[2]=(byte)(fragment.length>>>8);fragment[3]=(byte)fragment.length;fixHeader(fragment);sender.send(fragment);}
    }
    public synchronized void receive(byte[] ip) throws Exception{
        if(closed||ip.length<20||((ip[0]>>>4)&15)!=4)return;int header=(ip[0]&15)*4;if(header<20||header>ip.length||u16(ip,2)!=ip.length||checksum(cut(ip,0,header))!=0||!Arrays.equals(local,cut(ip,16,4)))return;
        int flags=u16(ip,6);if((flags&0x3fff)!=0){ip=reassemble(ip,header,flags);if(ip==null)return;header=(ip[0]&15)*4;}
        ImsIpsec s=security;if(s!=null)ip=s.inbound(ip);
        int proto=u(ip[9]);byte[] transport=cut(ip,header,ip.length-header);if(proto==17){if(transport.length<8||u16(transport,4)!=transport.length)return;byte[] pseudo=cat(cut(ip,12,8),b(0,17),n16(transport.length),transport);if(u16(transport,6)!=0&&checksum(pseudo)!=0)return;Port p=ports.get(u16(transport,2));if(p!=null)p.queue.offer(new Datagram(InetAddress.getByAddress(cut(ip,12,4)).getHostAddress(),u16(transport,0),cut(transport,8,transport.length-8)));}
        else if(proto==6){if(transport.length<20||checksum(cat(cut(ip,12,8),b(0,6),n16(transport.length),transport))!=0)return;int localPort=u16(transport,2);TcpStream stream=tcp.get(localPort);if(stream!=null)stream.receive(cut(ip,12,4),transport);else if((transport[13]&0x12)==2){TcpListener listener=listeners.get(localPort);if(listener!=null&&listener.queue.remainingCapacity()>0){TcpStream accepted=new TcpStream(this,localPort,InetAddress.getByAddress(cut(ip,12,4)).getHostAddress(),u16(transport,0));tcp.put(localPort,accepted);accepted.accept(u32(transport,4));listener.queue.offer(accepted);}}}
    }
    private byte[] reassemble(byte[] ip,int header,int flags) throws Exception{
        fragments.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().created>30000);if(fragments.size()>=64)fragments.clear();FragmentKey key=new FragmentKey(hex(cut(ip,12,4)),u16(ip,4),u(ip[9]));Fragments entry=fragments.computeIfAbsent(key,k->new Fragments());int offset=(flags&0x1fff)*8;byte[] piece=cut(ip,header,ip.length-header);if(offset+piece.length>65515||(flags&0x2000)!=0&&piece.length%8!=0){fragments.remove(key);return null;}if(entry.pieces.containsKey(offset)){if(!Arrays.equals(entry.pieces.get(offset),piece))fragments.remove(key);return null;}for(var v:entry.pieces.entrySet())if(offset<v.getKey()+v.getValue().length&&v.getKey()<offset+piece.length){fragments.remove(key);return null;}entry.pieces.put(offset,piece);if(offset==0)entry.header=cut(ip,0,header);if((flags&0x2000)==0)entry.total=offset+piece.length;if(entry.total<0||entry.header==null)return null;int p=0;for(var v:entry.pieces.entrySet()){if(v.getKey()!=p)return null;p+=v.getValue().length;}if(p!=entry.total)return null;ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(entry.header);for(byte[] v:entry.pieces.values())b.write(v);byte[] result=b.toByteArray();result[2]=(byte)(result.length>>>8);result[3]=(byte)result.length;result[6]=result[7]=0;fixHeader(result);fragments.remove(key);return result;
    }
    static int checksum(byte[] data){long sum=0;for(int p=0;p<data.length;p+=2)sum+=(u(data[p])<<8)|(p+1<data.length?u(data[p+1]):0);while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16);return (~(int)sum)&65535;}
    static void fixHeader(byte[] ip){ip[10]=ip[11]=0;int c=checksum(cut(ip,0,(ip[0]&15)*4));ip[10]=(byte)(c>>>8);ip[11]=(byte)c;}
    byte[] local(){return local.clone();}
    void removeTcp(int port,TcpStream stream){tcp.remove(port,stream);}
    public void close(){closed=true;for(Port p:List.copyOf(ports.values()))p.close();for(TcpStream t:List.copyOf(tcp.values()))t.close();for(TcpListener l:List.copyOf(listeners.values()))l.close();fragments.clear();ImsIpsec s=security;if(s!=null)s.close();}
}
