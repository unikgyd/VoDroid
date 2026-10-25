package io.vodroid.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** Negotiated AES-CBC / HMAC ESP tunnel mode, with a 64-packet replay window. */
public final class Esp implements AutoCloseable {
    private final byte[] inSpi,outSpi,inEnc,inMac,outEnc,outMac;
    private final IkeSuite suite;
    private long sendSeq,highest,window;
    byte[] inboundSpi(){return inSpi.clone();}
    byte[] outboundSpi(){return outSpi.clone();}
    IkeSuite suite(){return suite;}
    synchronized boolean needsRekey(){return sendSeq>=0xffff0000L;}
    public Esp(byte[] inSpi,byte[] outSpi,byte[] keys){
        this(inSpi,outSpi,keys,IkeSuite.DEFAULT);
    }
    public Esp(byte[] inSpi,byte[] outSpi,byte[] keys,IkeSuite suite){
        if(keys.length!=suite.childKeyLength()||inSpi.length!=4||outSpi.length!=4)throw new IllegalArgumentException("Child key length / SPI");
        this.suite=suite;int enc=suite.encryptionLength(),mac=suite.integrityLength(),half=enc+mac;
        this.inSpi=inSpi.clone();this.outSpi=outSpi.clone();outEnc=cut(keys,0,enc);outMac=cut(keys,enc,mac);inEnc=cut(keys,half,enc);inMac=cut(keys,half+enc,mac);
    }
    public synchronized byte[] encrypt(byte[] packet) throws Exception{
        if(packet.length<20||((packet[0]>>>4)&15)!=4)throw new IOException("IPv4 packet required");
        if(sendSeq>=0xffffffffL)throw new IOException("ESP sequence exhausted; reconnect");
        int padding=(16-(packet.length+2)%16)%16;byte[] padded=Arrays.copyOf(packet,packet.length+padding+2);for(int i=1;i<=padding;i++)padded[packet.length+i-1]=(byte)i;padded[padded.length-2]=(byte)padding;padded[padded.length-1]=4;
        byte[] iv=Crypto.random(16),a=cat(outSpi,n32(++sendSeq),iv,Crypto.aes(true,outEnc,iv,padded));return cat(a,suite.checksum(outMac,a));
    }
    public synchronized byte[] decrypt(byte[] a) throws Exception{
        int checksum=suite.checksumLength();if(a.length<40+checksum||(a.length-24-checksum)%16!=0||!MessageDigest.isEqual(inSpi,cut(a,0,4)))throw new IOException("Invalid ESP packet");
        long seq=u32(a,4);if(seq==0||seq<=highest&&(highest-seq>=64||(window&(1L<<(highest-seq)))!=0))throw new IOException("ESP replay rejected");
        if(!MessageDigest.isEqual(cut(a,a.length-checksum,checksum),suite.checksum(inMac,cut(a,0,a.length-checksum))))throw new IOException("ESP integrity failed");
        byte[] p=Crypto.aes(false,inEnc,cut(a,8,16),cut(a,24,a.length-24-checksum));int pad=u(p[p.length-2]);if(u(p[p.length-1])!=4||pad+2>p.length)throw new IOException("Unsupported ESP inner protocol");for(int i=1;i<=pad;i++)if(u(p[p.length-pad-2+i-1])!=i)throw new IOException("Invalid ESP padding");
        if(seq>highest){long delta=seq-highest;window=delta>=64?1:(window<<delta)|1;highest=seq;}else window|=1L<<(highest-seq);
        return cut(p,0,p.length-pad-2);
    }
    public void close(){for(byte[] a:new byte[][]{inEnc,inMac,outEnc,outMac})Arrays.fill(a,(byte)0);}
}
