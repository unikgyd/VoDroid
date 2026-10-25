package io.vodroid.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** TS 24.011 RP and TS 23.040 TPDU. Outbound uses UCS-2, with concatenation UDH. */
public final class SmsCodec {
    public record Part(int reference,int index,int total,byte[] rp){}
    public record Incoming(int rpReference,String sender,String text,int concat,int part,int total){}
    private static final String GSM="@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private SmsCodec(){}
    public static List<Part> submit(String to,String text,String smsc,int reference) throws IOException {
        if(!to.matches("\\+?[0-9]{1,20}")||!smsc.matches("\\+?[0-9]{1,20}")||text.isEmpty())throw new IOException("短信号码、内容或 SMSC 无效");
        if(text.codePoints().anyMatch(c->c>0xffff))throw new IOException("UCS-2 短信暂不支持表情等非 BMP 字符");
        int max=text.length()>70?67:70,total=(text.length()+max-1)/max;if(total>255)throw new IOException("短信过长");List<Part> out=new ArrayList<>();
        for(int i=0;i<total;i++){String chunk=text.substring(i*max,Math.min(text.length(),(i+1)*max));byte[] ud=chunk.getBytes(StandardCharsets.UTF_16BE);if(total>1)ud=cat(b(5,0,3,reference,total,i+1),ud);
            String digits=to.replace("+","");byte[] tpdu=cat(b(total>1?0x41:1,0,digits.length(),to.startsWith("+")?0x91:0x81),bcd(digits),b(0,8,ud.length),ud);
            byte[] sc=cat(b(smsc.startsWith("+")?0x91:0x81),bcd(smsc.replace("+","")));int mr=(reference+i)&255;
            out.add(new Part(mr,i+1,total,cat(b(0,mr,0,sc.length),sc,b(tpdu.length),tpdu)));
        }return out;
    }
    public static Incoming deliver(byte[] rp) throws IOException {
        if(rp.length<4||(rp[0]&7)!=1)throw new IOException("Not RP-DATA network to mobile");int ref=u(rp[1]),p=2;
        for(int i=0;i<2;i++){range(rp,p,1);int n=u(rp[p++]);range(rp,p,n);p+=n;}range(rp,p,1);int n=u(rp[p++]);if(n!=rp.length-p)throw new IOException("RP length mismatch");byte[] tp=cut(rp,p,n);range(tp,0,3);int fo=u(tp[0]);if((fo&3)!=0)throw new IOException("Unsupported inbound TPDU");int digits=u(tp[1]),ton=u(tp[2]),oa=(digits+1)/2;range(tp,3,oa);String sender;
        if((ton&0x70)==0x50)sender=gsm7(cut(tp,3,oa),digits*4/7,0);else{sender=bcd(cut(tp,3,oa));if(sender.length()>digits)sender=sender.substring(0,digits);if((ton&0x70)==0x10)sender="+"+sender;}
        p=3+oa;range(tp,p,10);int dcs=u(tp[p+1]);p+=9;int udl=u(tp[p++]),header=0,concat=-1,part=1,total=1;
        if((fo&0x40)!=0){range(tp,p,1);header=u(tp[p])+1;range(tp,p,header);for(int h=p+1;h<p+header;){range(tp,h,2);int ie=u(tp[h]),len=u(tp[h+1]);range(tp,h+2,len);if(h+2+len>p+header)throw new IOException("Invalid SMS UDH");if(ie==0&&len==3){concat=u(tp[h+2]);total=u(tp[h+3]);part=u(tp[h+4]);}if(ie==8&&len==4){concat=u16(tp,h+2);total=u(tp[h+4]);part=u(tp[h+5]);}h+=2+len;}}
        if(total==0||part==0||part>total)throw new IOException("Invalid concatenated SMS");String text;
        if((dcs&0x0c)==8){if(udl<header||(udl-header)%2!=0)throw new IOException("Invalid UCS-2 length");range(tp,p,udl);text=new String(tp,p+header,udl-header,StandardCharsets.UTF_16BE);}else if((dcs&0x0c)==0){int septets=(header*8+6)/7;if(udl<septets)throw new IOException("Invalid GSM7 UDL");byte[] data=cut(tp,p,tp.length-p);text=gsm7(data,udl-septets,septets*7);}else throw new IOException("Unsupported SMS DCS");
        return new Incoming(ref,sender,text,concat,part,total);
    }
    public static String gsm7(byte[] data,int count,int offset) throws IOException{
        if(count<0||(long)offset+count*7L>data.length*8L)throw new IOException("Truncated GSM7 data");StringBuilder s=new StringBuilder();boolean escape=false;
        for(int i=0;i<count;i++){int bit=offset+i*7,value=(u(data[bit/8])>>>(bit%8));if(bit%8>1&&bit/8+1<data.length)value|=u(data[bit/8+1])<<(8-bit%8);value&=127;if(escape){s.append(switch(value){case 10->'\f';case 20->'^';case 40->'{';case 41->'}';case 47->'\\';case 60->'[';case 61->'~';case 62->']';case 64->'|';case 101->'€';default->' ';});escape=false;}else if(value==27)escape=true;else s.append(GSM.charAt(value));}return s.toString();
    }
    public static byte[] ack(int reference){return b(2,reference);}
    public static byte[] error(int reference,int cause){return b(4,reference,1,cause&127);}
    public record Assembled(String id,String sender,String text){}
    private static String assemblyId(String[] ids){try{return "ims:"+hex(Crypto.digest("SHA-256",String.join("|",ids).getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}}
    public static final class Assembler {
        private record Key(String sender,int reference,int total){}
        private static final class Entry {final long time=System.currentTimeMillis();final String[] parts,ids;Entry(int n){parts=new String[n];ids=new String[n];}}
        private final LinkedHashMap<Key,Entry> pending=new LinkedHashMap<>();
        public synchronized String accept(Incoming in){Assembled result=accept(in,UUID.randomUUID().toString());return result==null?null:result.text();}
        public synchronized Assembled accept(Incoming in,String id){if(in.total()==1)return new Assembled(id,in.sender(),in.text());pending.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().time>24*60*60*1000L);Key key=new Key(in.sender(),in.concat(),in.total());Entry entry=pending.computeIfAbsent(key,k->new Entry(in.total()));entry.parts[in.part()-1]=in.text();entry.ids[in.part()-1]=id;while(pending.size()>128)pending.remove(pending.keySet().iterator().next());for(String p:entry.parts)if(p==null)return null;pending.remove(key);return new Assembled(assemblyId(entry.ids),in.sender(),String.join("",entry.parts));}
    }
}
