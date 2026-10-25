package io.vodroid.core;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static io.vodroid.core.Bytes.*;

/** Bounded stop-and-wait TCP transport for SIP; retransmits and handles peer retransmission. */
public final class TcpStream implements AutoCloseable {
    private final PacketNetwork network;
    private final int localPort,remotePort;
    private final byte[] remote;
    private final Socket systemSocket;
    private final BlockingQueue<byte[]> incoming=new ArrayBlockingQueue<>(256);
    private final Object state=new Object(),writer=new Object();
    private long sendSequence=Integer.toUnsignedLong(Crypto.RANDOM.nextInt()),receiveSequence,acknowledged;
    private volatile boolean established,closed,eof,synReceived;
    private volatile int peerWindow=65535;
    TcpStream(PacketNetwork n,int local,String host,int port) throws Exception{network=n;localPort=local;remotePort=port;remote=InetAddress.getByName(host).getAddress();systemSocket=null;input=makeInput();}
    TcpStream(Socket socket) throws IOException {network=null;localPort=socket.getLocalPort();remotePort=socket.getPort();remote=socket.getInetAddress().getAddress();systemSocket=socket;input=socket.getInputStream();established=true;}
    void connect() throws Exception{
        long seq; synchronized(state){seq=sendSequence;sendSequence=(seq+1)&0xffffffffL;}
        for(int i=0;i<4;i++){send(seq,0,2,new byte[0]);synchronized(state){if(!established&&!closed)state.wait(1500L<<i);if(established)return;if(closed)throw new IOException("TCP connection rejected");}}
        throw new SocketTimeoutException("IMS TCP connect timeout");
    }
    void accept(long sequence) throws Exception{synchronized(state){synReceived=true;receiveSequence=(sequence+1)&0xffffffffL;long seq=sendSequence;sendSequence=(sendSequence+1)&0xffffffffL;send(seq,receiveSequence,0x12,new byte[0]);}}
    private void send(long seq,long ack,int flags,byte[] payload) throws Exception{
        byte[] options=(flags&2)!=0?hex("020404B0"):new byte[0];byte[] segment=cat(n16(localPort),n16(remotePort),n32(seq),n32(ack),b((20+options.length)/4<<4,flags),n16(16384),new byte[4],options,payload);int c=PacketNetwork.checksum(cat(network.local(),remote,b(0,6),n16(segment.length),segment));segment[16]=(byte)(c>>>8);segment[17]=(byte)c;network.sendTransport(remote,6,segment);
    }
    void receive(byte[] source,byte[] segment) throws Exception{
        if(!Arrays.equals(source,remote)||u16(segment,0)!=remotePort)return;int header=(u(segment[12])>>>4)*4;if(header<20||header>segment.length)return;long seq=u32(segment,4),ack=u32(segment,8);int flags=u(segment[13]);
        synchronized(state){if(closed)return;if((flags&4)!=0){closed=true;incoming.offer(new byte[0]);state.notifyAll();return;}peerWindow=u16(segment,14);
            if(!established){if(synReceived){if((flags&2)!=0){send((sendSequence-1)&0xffffffffL,receiveSequence,0x12,new byte[0]);return;}if((flags&16)!=0&&ack==sendSequence&&seq==receiveSequence){acknowledged=ack;established=true;state.notifyAll();}else return;}else{if((flags&0x12)==0x12&&ack==sendSequence){receiveSequence=(seq+1)&0xffffffffL;acknowledged=ack;established=true;send(sendSequence,receiveSequence,16,new byte[0]);state.notifyAll();}return;}}
            if((flags&16)!=0){if(compare(ack,acknowledged)>=0&&compare(ack,sendSequence)<=0)acknowledged=ack;state.notifyAll();}
            int len=segment.length-header;
            if(seq==receiveSequence){if(len>0){if(incoming.offer(cut(segment,header,len)))receiveSequence=(receiveSequence+len)&0xffffffffL;else len=0;}if((flags&1)!=0&&seq+len==receiveSequence){receiveSequence=(receiveSequence+1)&0xffffffffL;eof=true;incoming.offer(new byte[0]);}}
            if(len>0||(flags&3)!=0)send(sendSequence,receiveSequence,16,new byte[0]);
        }
    }
    static int compare(long a,long b){return Integer.compare((int)(a-b),0);}
    public void write(byte[] data) throws Exception{
        if(systemSocket!=null){synchronized(writer){systemSocket.getOutputStream().write(data);systemSocket.getOutputStream().flush();}return;}
        synchronized(writer){for(int p=0;p<data.length;){int len=Math.min(1000,data.length-p);byte[] part=cut(data,p,len);long seq,target,ack;synchronized(state){long deadline=System.currentTimeMillis()+15000;while(peerWindow<len&&!closed){if(System.currentTimeMillis()>deadline)throw new SocketTimeoutException("TCP zero window");state.wait(1000);}if(closed)throw new IOException("TCP closed");seq=sendSequence;target=(seq+len)&0xffffffffL;sendSequence=target;ack=receiveSequence;}
                boolean done=false;for(int retry=0;retry<5;retry++){send(seq,ack,24,part);synchronized(state){if(compare(acknowledged,target)<0&&!closed)state.wait(Math.min(6000,700L<<retry));if(compare(acknowledged,target)>=0){done=true;break;}if(closed)throw new IOException("TCP closed");}}if(!done)throw new SocketTimeoutException("TCP send timed out");p+=len;}}
    }
    public final InputStream input;
    private InputStream makeInput(){return new InputStream(){byte[] buffer=new byte[0];int p;public int read() throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:u(b[0]);}public int read(byte[] b,int off,int len) throws IOException{if(len==0)return 0;while(p>=buffer.length){if((closed||eof)&&incoming.isEmpty())return -1;try{byte[] next=incoming.poll(30,TimeUnit.SECONDS);p=0;if(next==null)continue;buffer=next;if(buffer.length==0&&eof)return -1;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}int n=Math.min(len,buffer.length-p);System.arraycopy(buffer,p,b,off,n);p+=n;return n;}};}
    public void close(){if(systemSocket!=null){closed=true;try{systemSocket.close();}catch(IOException ignored){}return;}synchronized(state){if(!closed&&established)try{send(sendSequence,receiveSequence,20,new byte[0]);}catch(Exception ignored){}closed=true;state.notifyAll();}incoming.offer(new byte[0]);network.removeTcp(localPort,this);}
}
