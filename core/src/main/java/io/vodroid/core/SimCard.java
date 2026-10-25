package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** No key material is persisted or included in error messages. */
public final class SimCard implements AutoCloseable {
    public interface Transport extends AutoCloseable {
        int open(byte[] aid) throws Exception;
        byte[] transmit(int channel,byte[] command) throws Exception;
        void closeChannel(int channel) throws Exception;
        void close() throws Exception;
    }
    public static final class Aka implements AutoCloseable {
        public final byte[] res,ck,ik,auts;
        Aka(byte[] res,byte[] ck,byte[] ik,byte[] auts){this.res=res;this.ck=ck;this.ik=ik;this.auts=auts;}
        public boolean synchronizedOk(){return auts.length==0;}
        public void close(){for(byte[] v:new byte[][]{res,ck,ik,auts})Arrays.fill(v,(byte)0);}
    }
    public record Identity(String imsi,String iccid,String mcc,String mnc) {
        public Identity {if(!imsi.matches("[0-9]{14,15}")||!mcc.matches("[0-9]{3}")||!mnc.matches("[0-9]{2,3}"))throw new IllegalArgumentException("Invalid SIM identity");}
        public String realm(){return "wlan.mnc"+String.format(Locale.ROOT,"%03d",Integer.parseInt(mnc))+".mcc"+mcc+".3gppnetwork.org";}
        public String nai(){return "0"+imsi+"@"+realm();}
        public String imsDomain(){return "ims.mnc"+String.format(Locale.ROOT,"%03d",Integer.parseInt(mnc))+".mcc"+mcc+".3gppnetwork.org";}
        public String epdg(){return "epdg.epc.mnc"+String.format(Locale.ROOT,"%03d",Integer.parseInt(mnc))+".mcc"+mcc+".pub.3gppnetwork.org";}
    }
    private final Transport transport;
    private int channel=-1;
    private byte[] aid;
    public SimCard(Transport t){transport=t;}
    public synchronized void connect(String aid) throws Exception {
        if(channel>=0)throw new IllegalStateException("SIM session already open");
        this.aid=hex(aid);channel=transport.open(this.aid); if(channel<0)throw new IOException("USIM application unavailable");
    }
    public synchronized byte[] command(byte[] cmd) throws Exception {
        if(channel<0)throw new IOException("SIM disconnected");
        byte[] response=transport.transmit(channel,cmd.clone());
        if(response.length<2)throw new IOException("Short APDU response");
        if(u(response[response.length-2])==0x6c){byte[] fixed=cmd.clone();fixed[fixed.length-1]=response[response.length-1];response=transport.transmit(channel,fixed);}
        for(int i=0;i<16;i++){
            if(response.length<2)throw new IOException("Short APDU response");
            int sw=u(response[response.length-2]);
            if(sw!=0x61&&sw!=0x9f)break;
            byte[] next=transport.transmit(channel,b(0,0xc0,0,0,u(response[response.length-1])));
            response=cat(cut(response,0,response.length-2),next);
        }
        if(response.length<2||u16(response,response.length-2)!=0x9000)throw new IOException("SIM status "+(response.length>=2?hex(cut(response,response.length-2,2)):"short"));
        return cut(response,0,response.length-2);
    }
    private byte[] read(int file,int len) throws Exception {command(b(0,0xa4,0,4,2,file>>8,file));return command(b(0,0xb0,0,0,len));}
    public synchronized Identity identity(int mncOverride) throws Exception {
        byte[] imsi=read(0x6f07,9); if(imsi.length<2||u(imsi[0])+1>imsi.length)throw new IOException("Invalid EF IMSI");
        String digits=bcd(cut(imsi,1,u(imsi[0])));digits=digits.substring(1);
        int mncLength=mncOverride;
        if(mncLength==0){byte[] ad=read(0x6fad,4);mncLength=ad.length>=4?ad[3]&15:0;}
        if(mncLength!=2&&mncLength!=3)throw new IOException("EF AD has no MNC length; set 2 or 3 in settings");
        String iccid;
        try { command(hex("00A40004023F00"));iccid=bcd(read(0x2fe2,10)); }
        finally {
            // OMAPI prohibits SELECT by DF name in transmit(). Reopen through its
            // logical-channel API so AKA starts in the USIM application again.
            int previous=channel;channel=-1;transport.closeChannel(previous);
            channel=transport.open(aid);
            if(channel<0)throw new IOException("Cannot reopen USIM application");
        }
        return new Identity(digits,iccid,digits.substring(0,3),digits.substring(3,3+mncLength));
    }
    public synchronized Aka authenticate(byte[] rand,byte[] autn) throws Exception {
        if(rand.length!=16||autn.length!=16)throw new IllegalArgumentException("AKA RAND and AUTN must be 16 bytes");
        byte[] result=command(cat(hex("0088008122"),b(16),rand,b(16),autn,b(0)));
        try{return parseAka(result);}finally{Arrays.fill(result,(byte)0);}
    }
    public static Aka parseAka(byte[] data) throws IOException {
        if(data.length<2)throw new IOException("Invalid AKA result");
        if(u(data[0])==0xdc){if(u(data[1])!=14||data.length!=16)throw new IOException("Invalid AUTS");return new Aka(new byte[0],new byte[0],new byte[0],cut(data,2,14));}
        if(u(data[0])!=0xdb)throw new IOException("USIM rejected network authentication");
        int p=1;List<byte[]> fields=new ArrayList<>();
        for(int i=0;i<3;i++){if(p>=data.length)throw new IOException("Truncated AKA result");int n=u(data[p++]);if(p+n>data.length)throw new IOException("Truncated AKA field");fields.add(cut(data,p,n));p+=n;}
        if(fields.get(0).length<4||fields.get(0).length>16||fields.get(1).length!=16||fields.get(2).length!=16)throw new IOException("Invalid AKA field lengths");
        return new Aka(fields.get(0),fields.get(1),fields.get(2),new byte[0]);
    }
    public synchronized void close() throws Exception {if(channel>=0){try{transport.closeChannel(channel);}finally{channel=-1;}}}
}
