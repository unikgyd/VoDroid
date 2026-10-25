package io.vodroid.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.vodroid.core.Bytes.*;
import static io.vodroid.core.IkeWire.*;

/** RFC 7383 SK/SKF framing. Each fragment is authenticated before reassembly. */
final class IkeEncrypted {
    static final int MTU=1200,MAX_FRAGMENTS=64,MAX_MESSAGE=262144;
    record Opened(Message header,int first,int number,int total,byte[] plain){}
    record Decoded(Message header,List<Payload> payloads){}
    private IkeEncrypted(){}

    static List<byte[]> seal(Message meta,List<Payload> payloads,IkeSuite suite,byte[] encryption,byte[] integrity,boolean fragmentation)throws Exception{
        return seal(meta,payloads,suite,encryption,integrity,fragmentation,MTU);
    }
    static List<byte[]> seal(Message meta,List<Payload> payloads,IkeSuite suite,byte[] encryption,byte[] integrity,boolean fragmentation,int mtu)throws Exception{
        Chain chain=chain(payloads);byte[] plain=chain.bytes();
        if(plain.length>MAX_MESSAGE)throw new IOException("IKE message exceeds limit");
        int checksum=suite.checksumLength(),unfragmented=28+4+16+((plain.length+16)/16)*16+checksum;
        boolean split=fragmentation&&unfragmented>mtu;
        if(!split&&unfragmented>65503)throw new IOException("IKE message requires negotiated fragmentation");
        int chunk=split?((mtu-28-8-16-checksum)/16)*16-1:Math.max(1,plain.length);
        if(chunk<1)throw new IOException("IKE fragment MTU too small");
        int total=split?(plain.length+chunk-1)/chunk:1;
        if(total>MAX_FRAGMENTS)throw new IOException("Too many IKE fragments");
        List<byte[]> packets=new ArrayList<>();
        for(int index=0,offset=0;index<total;index++){
            int length=Math.min(chunk,plain.length-offset),padding=(16-(length+1)%16)%16;
            byte[] input=new byte[length+padding+1];System.arraycopy(plain,offset,input,0,length);input[input.length-1]=(byte)padding;
            byte[] iv=Crypto.random(16),cipher=Crypto.aes(true,encryption,iv,input);Arrays.fill(input,(byte)0);
            int skLength=(split?8:4)+16+cipher.length+checksum;
            byte[] prefix=cat(b(index==0?chain.first():0,0),n16(skLength),split?cat(n16(index+1),n16(total)):new byte[0]);
            byte[] packet=cat(header(meta.initiator(),meta.responder(),split?53:46,meta.exchange(),meta.flags(),meta.id(),skLength),prefix,iv,cipher);
            packets.add(cat(packet,suite.checksum(integrity,packet)));offset+=length;
        }
        Arrays.fill(plain,(byte)0);return List.copyOf(packets);
    }
    static Opened open(byte[] wire,IkeSuite suite,byte[] encryption,byte[] integrity,boolean fragmentation)throws Exception{
        Message message=decode(wire);boolean fragment=message.first()==53;
        if(message.first()!=46&&!fragment||fragment&&!fragmentation)throw new IOException("Unexpected encrypted IKE payload");
        byte[] body=message.body();int checksum=suite.checksumLength(),prefix=fragment?8:4;
        if(body.length<prefix+32+checksum||u16(body,2)!=body.length||(body.length-prefix-16-checksum)%16!=0)throw new IOException("Invalid encrypted IKE length");
        if(!MessageDigest.isEqual(cut(wire,wire.length-checksum,checksum),suite.checksum(integrity,cut(wire,0,wire.length-checksum))))throw new IOException("IKE integrity check failed");
        int number=fragment?u16(body,4):1,total=fragment?u16(body,6):1;
        if(number<1||total<1||number>total||total>MAX_FRAGMENTS||number>1&&body[0]!=0)throw new IOException("Invalid IKE fragment number or payload");
        byte[] plain=Crypto.aes(false,encryption,cut(body,prefix,16),cut(body,prefix+16,body.length-prefix-16-checksum));
        int padding=u(plain[plain.length-1]);if(padding+1>plain.length)throw new IOException("Invalid IKE padding");
        byte[] decoded=cut(plain,0,plain.length-padding-1);Arrays.fill(plain,(byte)0);
        return new Opened(message,u(body[0]),number,total,decoded);
    }

    static final class Reassembly implements AutoCloseable {
        private record Key(String i,String r,int exchange,int flags,long id){}
        private static final class Parts {final long created;final int total;final Map<Integer,byte[]> pieces=new HashMap<>();int first,bytes;Parts(long now,int total){created=now;this.total=total;}void clear(){pieces.values().forEach(a->Arrays.fill(a,(byte)0));}}
        private final Map<Key,Parts> messages=new HashMap<>();
        Decoded accept(byte[] wire,IkeSuite suite,byte[] encryption,byte[] integrity,boolean fragmentation,long now)throws Exception{
            Opened opened=open(wire,suite,encryption,integrity,fragmentation);Message h=opened.header();Key key=new Key(hex(h.initiator()),hex(h.responder()),h.exchange(),h.flags(),h.id());
            for(Iterator<Parts> iterator=messages.values().iterator();iterator.hasNext();){Parts item=iterator.next();if(now-item.created>TimeUnit.SECONDS.toNanos(30)){item.clear();iterator.remove();}}
            if(h.first()==46){Parts previous=messages.remove(key);if(previous!=null)previous.clear();try{return new Decoded(h,parse(opened.first(),opened.plain()));}finally{Arrays.fill(opened.plain(),(byte)0);}}
            Parts parts=messages.get(key);
            if(parts!=null&&parts.total!=opened.total()){
                // A smaller MTU can cause the sender to retransmit with more fragments.
                if(opened.total()<parts.total){Arrays.fill(opened.plain(),(byte)0);return null;}
                parts.clear();messages.remove(key);parts=null;
            }
            if(parts==null){if(messages.size()>=4){Arrays.fill(opened.plain(),(byte)0);throw new IOException("IKE reassembly capacity exceeded");}parts=new Parts(now,opened.total());messages.put(key,parts);}
            byte[] old=parts.pieces.get(opened.number());
            if(old!=null){boolean equal=Arrays.equals(old,opened.plain())&&(opened.number()!=1||parts.first==opened.first());Arrays.fill(opened.plain(),(byte)0);if(!equal)throw new IOException("Conflicting authenticated IKE fragments");return null;}
            if(parts.bytes+opened.plain().length>MAX_MESSAGE){Arrays.fill(opened.plain(),(byte)0);parts.clear();messages.remove(key);throw new IOException("IKE reassembly message exceeds limit");}
            parts.pieces.put(opened.number(),opened.plain());parts.bytes+=opened.plain().length;if(opened.number()==1)parts.first=opened.first();
            if(parts.pieces.size()!=parts.total)return null;
            byte[] combined=new byte[parts.bytes];int position=0;
            for(int n=1;n<=parts.total;n++){byte[] piece=parts.pieces.get(n);System.arraycopy(piece,0,combined,position,piece.length);position+=piece.length;}
            messages.remove(key);parts.clear();try{return new Decoded(h,parse(parts.first,combined));}finally{Arrays.fill(combined,(byte)0);}
        }
        public void close(){messages.values().forEach(Parts::clear);messages.clear();}
    }
}
