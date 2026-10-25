package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static io.vodroid.core.Bytes.*;

public final class RtpEndpoint implements AutoCloseable {
    public record Format(String name,int rate,int payload,boolean octetAligned){}
    private final PacketNetwork network;
    private final PacketNetwork.Port port;
    private final Consumer<byte[]> downlink;
    private final int ssrc=Crypto.RANDOM.nextInt();
    private int sequence=Crypto.RANDOM.nextInt(65536);
    private long timestamp=Integer.toUnsignedLong(Crypto.RANDOM.nextInt());
    private final AtomicBoolean running=new AtomicBoolean(true);
    private volatile String remote;
    private volatile int remotePort;
    private volatile Format format;
    public RtpEndpoint(PacketNetwork net,Consumer<byte[]> downlink) throws Exception{network=net;port=net.openUdp(0);this.downlink=downlink;Thread t=new Thread(this::receive,"vodroid-rtp");t.setDaemon(true);t.start();}
    public Format format(){return format;}
    public String sdp() throws Exception {
        return "v=0\r\no=vodroid "+Integer.toUnsignedString(ssrc)+" 1 IN IP4 "+network.address()+"\r\ns=VoDroid\r\nc=IN IP4 "+network.address()+"\r\nt=0 0\r\nm=audio "+port.number+" RTP/AVP 97 96 8 0 101\r\na=rtpmap:97 AMR-WB/16000\r\na=fmtp:97 octet-align=1\r\na=rtpmap:96 AMR/8000\r\na=fmtp:96 octet-align=1\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-15\r\na=ptime:20\r\na=sendrecv\r\n";
    }
    public String answerSdp() throws Exception {if(format==null)throw new IOException("No audio format");Format f=format;return "v=0\r\no=vodroid "+Integer.toUnsignedString(ssrc)+" 1 IN IP4 "+network.address()+"\r\ns=VoDroid\r\nc=IN IP4 "+network.address()+"\r\nt=0 0\r\nm=audio "+port.number+" RTP/AVP "+f.payload()+"\r\na=rtpmap:"+f.payload()+" "+f.name()+"/"+f.rate()+"\r\n"+(f.name().startsWith("AMR")?"a=fmtp:"+f.payload()+" octet-align=1\r\n":"")+"a=ptime:20\r\na=sendrecv\r\n";}
    public void negotiate(String sdp) throws Exception{
        if(sdp.contains("RTP/SAVP")||sdp.contains("a=crypto:"))throw new IOException("SRTP not supported by this build");
        Map<Integer,Format> formats=new HashMap<>();formats.put(0,new Format("PCMU",8000,0,true));formats.put(8,new Format("PCMA",8000,8,true));Map<Integer,String> params=new HashMap<>();List<Integer> offered=new ArrayList<>();String host=null;int target=0;boolean audio=false;
        for(String l:sdp.split("\r?\n")){if(l.startsWith("m=")){audio=l.startsWith("m=audio ");if(audio){String[] p=l.substring(2).trim().split("\\s+");if(p.length<4||!p[2].equals("RTP/AVP"))throw new IOException("Unsupported RTP profile");target=Integer.parseInt(p[1]);for(int i=3;i<p.length;i++)offered.add(Integer.parseInt(p[i]));}}else if(l.startsWith("c=IN IP4 ")&&(audio||target==0))host=l.substring(9).trim();else if(audio&&l.startsWith("a=rtpmap:")){String[] p=l.substring(9).split("[ /]",4);int payload=Integer.parseInt(p[0]);if(p.length>=3)formats.put(payload,new Format(p[1].toUpperCase(Locale.ROOT),Integer.parseInt(p[2]),payload,false));}else if(audio&&l.startsWith("a=fmtp:")){int p=l.indexOf(' ',7);if(p>0)params.put(Integer.parseInt(l.substring(7,p)),l.substring(p+1));}}
        if(host==null||target<1||target>65535)throw new IOException("No usable SDP media endpoint");Format selected=null;
        for(int id:offered){Format f=formats.get(id);if(f==null)continue;if(f.name().equals("PCMA")||f.name().equals("PCMU")){selected=f;break;}if((f.name().equals("AMR")&&f.rate()==8000||f.name().equals("AMR-WB")&&f.rate()==16000)&&params.getOrDefault(id,"").replace(" ","").contains("octet-align=1")){selected=new Format(f.name(),f.rate(),id,true);break;}}
        if(selected==null)throw new IOException("Carrier offered no supported audio codec");remote=host;remotePort=target;format=selected;
    }
    public synchronized void send(byte[] frame) throws Exception{
        Format f=format;if(!running.get()||f==null||remote==null)return;byte[] payload=frame;if(f.name().startsWith("AMR")){if(frame.length<2)return;payload=cat(b(0xf0,frame[0]&0x7c),cut(frame,1,frame.length-1));}
        byte[] packet=cat(b(0x80,f.payload()),n16(sequence++),n32(timestamp),n32(ssrc),payload);timestamp=(timestamp+f.rate()/50)&0xffffffffL;port.send(remote,remotePort,packet);
    }
    private void receive(){int highest=-1;long peerSsrc=-1;while(running.get())try{PacketNetwork.Datagram d=port.receive(1000);Format f=format;if(f==null||!d.host().equals(remote)||d.port()!=remotePort)continue;byte[] a=d.data();if(a.length<12||(u(a[0])>>>6)!=2||(a[1]&127)!=f.payload())continue;int h=12+(a[0]&15)*4;range(a,0,h);if((a[0]&16)!=0){range(a,h,4);h+=4+u16(a,h+2)*4;}int end=a.length;if((a[0]&32)!=0){int padding=u(a[a.length-1]);if(padding<1||padding>end-h)continue;end-=padding;}range(a,h,end-h);int seq=u16(a,2);long ssrc=u32(a,8);if(peerSsrc!=ssrc){peerSsrc=ssrc;highest=-1;}if(highest>=0&&(short)(seq-highest)<=0)continue;highest=seq;byte[] payload=cut(a,h,end-h);if(f.name().startsWith("AMR")){if(payload.length<3||(payload[1]&128)!=0)continue;payload=cat(b(payload[1]&0x7c),cut(payload,2,payload.length-2));}downlink.accept(payload);}catch(java.net.SocketTimeoutException ignored){}catch(Exception e){if(!running.get())break;}}
    public void close(){running.set(false);port.close();}
    public static byte[] encodeG711(short[] pcm,boolean alaw){byte[] out=new byte[pcm.length];for(int i=0;i<pcm.length;i++)out[i]=(byte)(alaw?linearA(pcm[i]):linearU(pcm[i]));return out;}
    public static short[] decodeG711(byte[] data,boolean alaw){short[] out=new short[data.length];for(int i=0;i<data.length;i++){int v=u(data[i]);if(alaw){v^=0x55;int t=(v&15)<<4,seg=(v&0x70)>>4;t+=8;if(seg>=1)t+=256;if(seg>1)t<<=seg-1;out[i]=(short)((v&128)!=0?t:-t);}else{v=(~v)&255;int t=((v&15)<<3)+132;t<<=(v&0x70)>>4;out[i]=(short)((v&128)!=0?132-t:t-132);}}return out;}
    private static int linearA(short sample){int pcm=sample,mask;if(pcm>=0)mask=0xd5;else{mask=0x55;pcm=-pcm-1;}pcm=Math.min(pcm,32767);int seg=0;for(int limit=255;pcm>limit&&seg<7;limit=(limit<<1)|1)seg++;int value=seg<<4;value|=seg<2?(pcm>>4)&15:(pcm>>(seg+3))&15;return value^mask;}
    private static int linearU(short sample){int pcm=sample,mask;if(pcm<0){pcm=-pcm;mask=0x7f;}else mask=0xff;pcm=Math.min(pcm,32635)+132;int seg=0;for(int limit=255;pcm>limit&&seg<7;limit=(limit<<1)|1)seg++;return ((seg<<4)|((pcm>>(seg+3))&15))^mask;}
}
