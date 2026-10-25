package io.vodroid.core;

import java.io.IOException;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** RFC 3329 / TS 33.203 ESP transport with null encryption inside the ePDG tunnel. */
public final class ImsIpsec implements AutoCloseable {
    public final long spiClient=(Crypto.RANDOM.nextInt()&0x7fffffffL)+256,spiServer=(Crypto.RANDOM.nextInt()&0x7fffffffL)+256;
    public final int clientPort,serverPort;
    private byte[] integrity,peer;
    private String mac;
    private long peerClient,peerServer,seqClient,seqServer,highestClient,highestServer,windowClient,windowServer;
    private int remoteClient,remoteServer;
    private volatile boolean active;
    public ImsIpsec(int client,int server){clientPort=client;serverPort=server;}
    public String offer(){String params=";ealg=null;spi-c="+spiClient+";spi-s="+spiServer+";port-c="+clientPort+";port-s="+serverPort;return "ipsec-3gpp;alg=hmac-sha-1-96"+params+", ipsec-3gpp;alg=hmac-md5-96"+params;}
    public synchronized String install(List<String> security,byte[] ik,String peerIp) throws Exception{
        if(ik.length!=16)throw new IOException("IMS integrity key missing");
        String selected=null;Map<String,String> best=null;double q=-1;
        for(String line:security)for(String value:line.split(",")){String entry=value.trim();if(!entry.toLowerCase(Locale.ROOT).startsWith("ipsec-3gpp"))continue;Map<String,String> p=SipMessage.parameters(entry.substring(11));String alg=p.get("alg");if(!"null".equals(p.get("ealg"))||!("hmac-md5-96".equals(alg)||"hmac-sha-1-96".equals(alg)))continue;double score=Double.parseDouble(p.getOrDefault("q","1"));if(score>q){q=score;selected=entry;best=p;}}
        if(best==null)throw new IOException("P-CSCF offered no supported IMS IPsec algorithm");
        peerClient=Long.parseLong(best.get("spi-c"));peerServer=Long.parseLong(best.get("spi-s"));remoteClient=Integer.parseInt(best.get("port-c"));remoteServer=Integer.parseInt(best.get("port-s"));if(peerClient<256||peerClient>0xffffffffL||peerServer<256||peerServer>0xffffffffL||remoteClient<1||remoteClient>65535||remoteServer<1||remoteServer>65535)throw new IOException("Invalid IMS IPsec parameters");
        mac=best.get("alg").equals("hmac-md5-96")?"HmacMD5":"HmacSHA1";integrity=Arrays.copyOf(ik,mac.equals("HmacSHA1")?20:16);peer=InetAddress.getByName(peerIp).getAddress();active=true;return selected;
    }
    public boolean active(){return active;}
    public int remoteServer(){return remoteServer;}
    private boolean peer(byte[] ip,boolean outbound){return Arrays.equals(cut(ip,outbound?16:12,4),peer);}
    private static void ipChecksum(byte[] ip,int header){ip[10]=ip[11]=0;long sum=0;for(int p=0;p<header;p+=2)sum+=u16(ip,p);while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16);int checksum=(int)~sum;ip[10]=(byte)(checksum>>>8);ip[11]=(byte)checksum;}
    public synchronized byte[] outbound(byte[] ip) throws Exception{
        if(!active||ip.length<28||((ip[0]>>>4)&15)!=4||!peer(ip,true))return ip;int ihl=(ip[0]&15)*4;if(ihl<20||ihl+8>ip.length)throw new IOException("Invalid IPv4 header");int proto=u(ip[9]);if(proto!=17&&proto!=6)return ip;int source=u16(ip,ihl),dest=u16(ip,ihl+2);boolean client=source==clientPort&&dest==remoteServer,server=source==serverPort&&dest==remoteClient;if(!client&&!server)return ip;if((u16(ip,6)&0x3fff)!=0)throw new IOException("Fragmented IMS packet unsupported");
        long seq=client?++seqClient:++seqServer;if(seq>0xffffffffL)throw new IOException("IMS ESP sequence exhausted");byte[] payload=cut(ip,ihl,ip.length-ihl);int padding=(4-(payload.length+2)%4)%4;byte[] pad=new byte[padding];for(int i=0;i<pad.length;i++)pad[i]=(byte)(i+1);byte[] esp=cat(n32(client?peerServer:peerClient),n32(seq),payload,pad,b(padding,proto));esp=cat(esp,cut(Crypto.mac(mac,integrity,esp),0,12));byte[] result=cat(cut(ip,0,ihl),esp);result[9]=50;result[2]=(byte)(result.length>>>8);result[3]=(byte)result.length;ipChecksum(result,ihl);return result;
    }
    public synchronized byte[] inbound(byte[] ip) throws Exception{
        if(!active||ip.length<20||((ip[0]>>>4)&15)!=4||!peer(ip,false))return ip;int ihl=(ip[0]&15)*4;if(u(ip[9])!=50){if((u(ip[9])==17||u(ip[9])==6)&&ip.length>=ihl+4){int port=u16(ip,ihl+2);if(port==clientPort||port==serverPort)throw new IOException("Unprotected IMS packet rejected");}return ip;}
        if(ihl<20||ip.length<ihl+24)throw new IOException("Short IMS ESP packet");byte[] esp=cut(ip,ihl,ip.length-ihl);long spi=u32(esp,0),seq=u32(esp,4);boolean client=spi==spiClient;if(!client&&spi!=spiServer)throw new IOException("Unknown IMS SPI");long high=client?highestClient:highestServer,window=client?windowClient:windowServer;if(seq==0||seq<=high&&(high-seq>=64||(window&(1L<<(high-seq)))!=0))throw new IOException("IMS ESP replay");if(!MessageDigest.isEqual(cut(esp,esp.length-12,12),cut(Crypto.mac(mac,integrity,cut(esp,0,esp.length-12)),0,12)))throw new IOException("IMS ESP integrity failed");int pad=u(esp[esp.length-14]),proto=u(esp[esp.length-13]);if(pad+22>esp.length||proto!=17&&proto!=6)throw new IOException("IMS ESP payload invalid");for(int i=1;i<=pad;i++)if(u(esp[esp.length-14-pad+i-1])!=i)throw new IOException("IMS ESP padding invalid");byte[] payload=cut(esp,8,esp.length-22-pad);if(payload.length<4||u16(payload,0)!=(client?remoteServer:remoteClient)||u16(payload,2)!=(client?clientPort:serverPort))throw new IOException("IMS ESP port policy mismatch");
        if(seq>high){window=seq-high>=64?1:(window<<(seq-high))|1;high=seq;}else window|=1L<<(high-seq);if(client){highestClient=high;windowClient=window;}else{highestServer=high;windowServer=window;}
        byte[] out=cat(cut(ip,0,ihl),payload);out[9]=(byte)proto;out[2]=(byte)(out.length>>>8);out[3]=(byte)out.length;ipChecksum(out,ihl);return out;
    }
    public synchronized void close(){active=false;if(integrity!=null)Arrays.fill(integrity,(byte)0);}
}
