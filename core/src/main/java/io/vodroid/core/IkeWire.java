package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import static io.vodroid.core.Bytes.*;

public final class IkeWire {
    public record Payload(int type,byte[] body){}
    public record Chain(int first,byte[] bytes){}
    public record Message(byte[] initiator,byte[] responder,int exchange,int flags,long id,int first,byte[] body){}
    public static Payload p(int type,byte[] body){return new Payload(type,body);}
    public static Chain chain(List<Payload> ps){if(ps.size()>64)throw new IllegalArgumentException("Too many IKE payloads");byte[] out=new byte[0];for(int i=0;i<ps.size();i++){Payload p=ps.get(i);if(p.body.length>65531||p.type<1||p.type>255)throw new IllegalArgumentException("Invalid IKE payload size or type");out=cat(out,b(i+1<ps.size()?ps.get(i+1).type:0,0),n16(p.body.length+4),p.body);}return new Chain(ps.isEmpty()?0:ps.get(0).type,out);}
    public static List<Payload> parse(int first,byte[] a) throws IOException{
        List<Payload> ps=new ArrayList<>();int type=first,p=0;
        while(type!=0){if(ps.size()>64||p+4>a.length)throw new IOException("Invalid IKE payload chain");int next=u(a[p]),n=u16(a,p+2);if(n<4||p+n>a.length)throw new IOException("Invalid IKE payload length");if((u(a[p+1])&128)!=0&&!Set.of(33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,53).contains(type))throw new IOException("Unknown critical IKE payload");ps.add(p(type,cut(a,p+4,n-4)));p+=n;type=next;}
        if(p!=a.length)throw new IOException("Trailing IKE bytes");return ps;
    }
    public static byte[] get(List<Payload> ps,int type){for(Payload p:ps)if(p.type==type)return p.body;return null;}
    public static byte[] header(byte[] i,byte[] r,int first,int exchange,int flags,long id,int bodyLength){return cat(i,r,b(first,0x20,exchange,flags),n32(id),n32(28L+bodyLength));}
    public static byte[] encode(byte[] i,byte[] r,int exchange,int flags,long id,List<Payload> ps){Chain c=chain(ps);return cat(header(i,r,c.first,exchange,flags,id,c.bytes.length),c.bytes);}
    public static Message decode(byte[] a) throws IOException{if(a.length<28||u32(a,24)!=a.length||u(a[17])!=0x20)throw new IOException("Invalid IKE header");return new Message(cut(a,0,8),cut(a,8,8),u(a[18]),u(a[19]),u32(a,20),u(a[16]),cut(a,28,a.length-28));}
    private static byte[] transform(int type,int id,boolean aes,boolean last){byte[] attr=aes?hex("800E0080"):new byte[0];return cat(b(last?0:3,0),n16(8+attr.length),b(type,0),n16(id),attr);}
    public static byte[] proposal(boolean ike,byte[] spi){byte[] transforms=ike?cat(transform(1,12,true,false),transform(2,5,false,false),transform(3,12,false,false),transform(4,14,false,true)):cat(transform(1,12,true,false),transform(3,12,false,false),transform(5,0,false,true));return cat(b(0,0),n16(8+spi.length+transforms.length),b(1,ike?1:3,spi.length,ike?4:3),spi,transforms);}
    /** Prefer SHA-256, while offering the HMAC-SHA1 suites used by existing ePDGs. */
    public static byte[] offer(boolean ike,byte[] spi,int group){
        return offer(ike,spi,group,false);
    }
    public static byte[] offer(boolean ike,byte[] spi,int group,boolean legacyFirst){
        List<byte[]> transforms=new ArrayList<>();
        for(int bits:new int[]{128,256})transforms.add(cat(b(3,0,0,12,1,0,0,12),n16(0x800e),n16(bits)));
        if(ike)for(int prf:legacyFirst?new int[]{2,5}:new int[]{5,2})transforms.add(transform(2,prf,false,false));
        for(int integrity:legacyFirst?new int[]{2,12}:new int[]{12,2})transforms.add(transform(3,integrity,false,false));
        transforms.add(transform(ike?4:5,ike?group:0,false,true));
        byte[] encoded=new byte[0];for(byte[] transform:transforms)encoded=cat(encoded,transform);
        return cat(b(0,0),n16(8+spi.length+encoded.length),b(1,ike?1:3,spi.length,transforms.size()),spi,encoded);
    }
    public record Selection(IkeSuite suite,byte[] spi){}
    public static Selection select(byte[] sa,boolean ike,int offeredGroup)throws IOException{
        if(sa==null||sa.length<8||u(sa[0])!=0||u16(sa,2)!=sa.length||u(sa[4])!=1||u(sa[5])!=(ike?1:3))throw new IOException("Invalid selected SA");
        int spiLength=u(sa[6]);if(spiLength!=(ike?0:4)||sa.length<8+spiLength)throw new IOException("Invalid SA SPI");
        int count=0,bits=0;Map<Integer,Integer> selected=new HashMap<>();
        for(int pos=8+spiLength;pos<sa.length;){
            range(sa,pos,8);int length=u16(sa,pos+2),type=u(sa[pos+4]),id=u16(sa,pos+6);
            if(length<8||pos+length>sa.length||u(sa[pos])!=(pos+length<sa.length?3:0)||selected.put(type,id)!=null)throw new IOException("Invalid selected transform");
            if(type==1){if(id!=12||length!=12||u16(sa,pos+8)!=0x800e)throw new IOException("Unsupported encryption proposal");bits=u16(sa,pos+10);}
            else if(length!=8)throw new IOException("Unexpected transform attributes");
            pos+=length;count++;
        }
        if(count!=u(sa[7])||count!=(ike?4:selected.containsKey(5)?3:2)||!selected.containsKey(1)||!selected.containsKey(3))throw new IOException("Incomplete selected suite");
        if(ike){if(!selected.containsKey(2)||!Objects.equals(selected.get(4),offeredGroup)||selected.containsKey(5))throw new IOException("Selected DH group was not offered");}
        else if(selected.containsKey(2)||selected.containsKey(4)||selected.getOrDefault(5,0)!=0)throw new IOException("Unsupported ESP transform");
        try{return new Selection(new IkeSuite(bits,ike?selected.get(2):0,selected.get(3),ike?offeredGroup:0),cut(sa,8,spiLength));}
        catch(IllegalArgumentException error){throw new IOException("Selected algorithms were not offered",error);}
    }
    public static byte[] selectedSpi(byte[] sa,boolean ike) throws IOException {
        if(sa==null||sa.length<8||u16(sa,2)!=sa.length||u(sa[5])!=(ike?1:3))throw new IOException("Invalid selected SA");
        int spiLen=u(sa[6]);if(spiLen!=(ike?0:4))throw new IOException("Invalid SA SPI");int pos=8+spiLen;Map<Integer,Integer> ts=new HashMap<>();
        while(pos<sa.length){range(sa,pos,8);int n=u16(sa,pos+2),type=u(sa[pos+4]),id=u16(sa,pos+6);if(n<8||pos+n>sa.length)throw new IOException("Bad SA transform");if(ts.put(type,id)!=null)throw new IOException("Duplicate SA transform");if(type==1&&(id!=12||n!=12||u16(sa,pos+8)!=0x800e||u16(sa,pos+10)!=128))throw new IOException("Unsupported encryption proposal");pos+=n;}
        if(!Objects.equals(ts.get(1),12)||!Objects.equals(ts.get(3),12)||(ike&&(!Objects.equals(ts.get(2),5)||!Objects.equals(ts.get(4),14)))||(!ike&&ts.containsKey(4)))throw new IOException("Unsupported SA algorithms");
        if(ts.getOrDefault(5,0)!=0)throw new IOException("ESN unsupported");return cut(sa,8,spiLen);
    }
    public static byte[] notify(int type,byte[] data){return cat(b(0,0),n16(type),data);}
    public static byte[] trafficSelector(){return cat(b(1,0,0,0),b(7,0,0,16,0,0,255,255),new byte[4],hex("FFFFFFFF"));}
}
