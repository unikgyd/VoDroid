package io.vodroid.core;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** EAP-AKA / AKA' full authentication with protected results and retransmission caching. */
public final class EapAka implements AutoCloseable {
    private final SimCard sim;
    private byte[] identity;
    private final EapPolicy policy;
    private int method=23;
    private boolean primeSelected;
    private List<Integer> proposedKdfs,acceptedKdfs;
    private final ByteArrayOutputStream identityTranscript=new ByteArrayOutputStream();
    private byte[] kaut,msk;
    private boolean challengeVerified;
    private boolean resultIndicated,protectedSuccess;
    private byte[] lastRequest,lastResponse;
    private int lastId=-1;
    public EapAka(SimCard sim,String nai){this(sim,nai,EapPolicy.AUTO);}
    public EapAka(SimCard sim,String nai,EapPolicy policy){this.sim=sim;this.policy=policy;identity=policy.identity(nai).getBytes(StandardCharsets.UTF_8);}
    public String method(){return method==50?"AKA′":"AKA";}
    public byte[] msk(){if(!challengeVerified||msk==null)throw new IllegalStateException("AKA has not authenticated");return msk.clone();}
    private byte[] packet(int id,int subtype,byte[] attrs){return cat(b(2,id),n16(8+attrs.length),b(method,subtype,0,0),attrs);}
    private byte[] attr(int type,byte[] data){int n=(data.length+5)/4*4;byte[] a=new byte[n];a[0]=(byte)type;a[1]=(byte)(n/4);System.arraycopy(data,0,a,2,data.length);return a;}
    public byte[] respond(byte[] eap) throws Exception{
        if(eap.length<4||u16(eap,2)!=eap.length)throw new IOException("Invalid EAP length");
        int code=u(eap[0]),id=u(eap[1]);
        if(code==3){if(eap.length!=4||id!=lastId||!challengeVerified||resultIndicated&&!protectedSuccess)throw new IOException("EAP success before authenticated result");return null;}
        if(code==4){clearKeys();throw new IOException("EAP authentication rejected");}
        if(code!=1||eap.length<5)throw new IOException("Invalid EAP request");
        if(Arrays.equals(eap,lastRequest))return lastResponse.clone();
        if(id==lastId)throw new IOException("EAP identifier reused with different request");
        byte[] response;
        try{response=answer(eap);}catch(IllegalArgumentException error){clearKeys();throw new IOException("Malformed EAP-AKA attributes",error);}catch(Exception error){clearKeys();throw error;}
        if(eap.length>=8&&response.length>=8&&u(response[4])==u(eap[4])&&u(response[5])==5){identityTranscript.writeBytes(eap);identityTranscript.writeBytes(response);}
        if(lastResponse!=null)Arrays.fill(lastResponse,(byte)0);
        lastRequest=eap.clone();lastResponse=response.clone();lastId=id;
        return response;
    }
    private byte[] answer(byte[] eap) throws Exception{
        int id=u(eap[1]);
        if(u(eap[4])==1){clearKeys();return cat(b(2,id),n16(5+identity.length),b(1),identity);}
        int requested=u(eap[4]);
        if(!policy.allows(requested)||primeSelected&&requested!=50){clearKeys();byte[] desired=policy==EapPolicy.AKA?b(23):policy==EapPolicy.AKA_PRIME||primeSelected?b(50):b(50,23);return cat(b(2,id),n16(5+desired.length),b(3),desired);}
        method=requested;if(method==50)primeSelected=true;
        if(eap.length<8)throw new IOException("Truncated AKA request");
        int subtype=u(eap[5]);Map<Integer,byte[]> attrs=new HashMap<>();List<Integer> kdfs=new ArrayList<>();int macPos=-1;
        for(int p=8;p<eap.length;){range(eap,p,2);int type=u(eap[p]),len=u(eap[p+1])*4;if(len<4)throw new IOException("Invalid AKA attribute");range(eap,p,len);
            if(type==24&&method==50){if(len!=4)throw new IOException("Invalid AT_KDF length");kdfs.add(u16(eap,p+2));}
            else if(attrs.put(type,cut(eap,p+2,len-2))!=null)throw new IOException("Duplicate AKA attribute");
            if(type==11){if(len!=20)throw new IOException("Invalid AKA MAC length");macPos=p+4;}
            if(type<128&&!Set.of(1,2,3,4,6,7,10,11,12,13,14,15,17,19,20,22,23,24).contains(type))throw new IOException("Unsupported mandatory AKA attribute");p+=len;
        }
        if(subtype==5){clearKeys();if(method==50&&identity.length>0&&identity[0]=='0')identity[0]='6';return packet(id,5,attr(14,cat(n16(identity.length),identity)));}
        if(subtype==12){byte[] n=attrs.get(12);if(n==null||n.length<2)throw new IOException("Missing AKA notification");int val=u16(n,0);if((val&0x4000)==0)verifyMac(eap,macPos);if((val&0x8000)==0||!challengeVerified)throw new IOException("AKA network notification failure");if(val==0x8000)protectedSuccess=true;return signed(id,12,new byte[0]);}
        if(subtype!=1)throw new IOException("Unsupported AKA exchange "+subtype);
        clearKeys();
        byte[] rand=attrs.get(1),autn=attrs.get(2),mac=attrs.get(11);if(rand==null||autn==null||rand.length!=18||autn.length!=18||mac==null||mac.length!=18)throw new IOException("Invalid AKA challenge attributes");
        byte[] network=null;
        if(method==50){
            if(kdfs.isEmpty())throw new IOException("AKA prime KDF missing");
            if(proposedKdfs!=null){List<Integer> expected=new ArrayList<>();expected.add(1);expected.addAll(proposedKdfs);if(!kdfs.equals(expected))throw new IOException("AKA prime KDF negotiation changed");proposedKdfs=null;acceptedKdfs=List.copyOf(kdfs);}
            else if(acceptedKdfs!=null){if(!acceptedKdfs.equals(kdfs))throw new IOException("AKA prime KDF changed during authentication");}
            else{
                if(new HashSet<>(kdfs).size()!=kdfs.size())throw new IOException("Duplicate AKA prime KDF");
                if(kdfs.get(0)!=1){if(!kdfs.contains(1))throw new IOException("Unsupported AKA prime KDF");proposedKdfs=List.copyOf(kdfs);return packet(id,1,attr(24,n16(1)));}
                acceptedKdfs=List.copyOf(kdfs);
            }
            byte[] input=attrs.get(23);if(input==null||input.length<3)throw new IOException("AKA prime network name missing");int length=u16(input,0);
            if(length==0||length>1024||length>input.length-2||input.length-2-length>3)throw new IOException("Invalid AKA prime network name");
            network=cut(input,2,length);StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(network));
            if((autn[8]&128)==0)throw new IOException("AKA prime AMF separation bit is not set");
        }else if(attrs.containsKey(23)||attrs.containsKey(24))throw new IOException("AKA prime attributes in EAP-AKA");
        byte[] checkcode=attrs.get(134);byte[] expectedCheck=identityTranscript.size()==0?new byte[0]:Crypto.digest(method==50?"SHA-256":"SHA-1",identityTranscript.toByteArray());
        if(checkcode!=null&&(checkcode.length!=2+expectedCheck.length||!MessageDigest.isEqual(cut(checkcode,2,checkcode.length-2),expectedCheck)))throw new IOException("AKA identity transcript mismatch");
        try(SimCard.Aka result=sim.authenticate(cut(rand,2,16),cut(autn,2,16))){
            if(!result.synchronizedOk()){byte[] response=attr(4,result.auts);if(method==50)for(int kdf:kdfs)response=cat(response,attr(24,n16(kdf)));return packet(id,4,response);}
            byte[] keys;
            if(method==50){keys=Crypto.akaPrimeKeys(result.ck,result.ik,cut(autn,2,16),network,identity);kaut=cut(keys,16,32);msk=cut(keys,80,64);}
            else{byte[] mk=Crypto.digest("SHA-1",cat(identity,result.ik,result.ck));keys=Crypto.akaPrf(mk);Arrays.fill(mk,(byte)0);kaut=cut(keys,16,16);msk=cut(keys,32,64);}
            Arrays.fill(keys,(byte)0);
            verifyMac(eap,macPos);challengeVerified=true;
            if(method==23&&policy!=EapPolicy.AKA&&attrs.containsKey(136)){byte[] bidding=attrs.get(136);if(bidding.length!=2||(u16(bidding,0)&0x8000)!=0){clearKeys();throw new IOException("AKA bidding-down protection rejected the method");}}
            byte[] res=attr(3,cat(n16(result.res.length*8),result.res));
            if(checkcode!=null)res=cat(res,attr(134,cat(b(0,0),expectedCheck)));
            // Negotiate protected result indication when requested.
            resultIndicated=attrs.containsKey(135);if(resultIndicated){if(attrs.get(135).length!=2)throw new IOException("Invalid protected-result attribute");res=cat(res,attr(135,b(0,0)));}
            return signed(id,1,res);
        }
    }
    private void verifyMac(byte[] eap,int pos) throws Exception{
        if(kaut==null||pos<0||pos+16>eap.length)throw new IOException("AKA MAC missing");
        byte[] copy=eap.clone(),received=cut(copy,pos,16);Arrays.fill(copy,pos,pos+16,(byte)0);
        if(!MessageDigest.isEqual(received,cut(Crypto.mac(method==50?"HmacSHA256":"HmacSHA1",kaut,copy),0,16)))throw new IOException("AKA network MAC invalid");
    }
    private byte[] signed(int id,int subtype,byte[] attrs) throws Exception{
        byte[] p=packet(id,subtype,cat(attrs,attr(11,new byte[18])));
        byte[] mac=Crypto.mac(method==50?"HmacSHA256":"HmacSHA1",kaut,p);System.arraycopy(mac,0,p,p.length-16,16);return p;
    }
    private void clearKeys(){if(kaut!=null)Arrays.fill(kaut,(byte)0);if(msk!=null)Arrays.fill(msk,(byte)0);kaut=null;msk=null;challengeVerified=false;resultIndicated=false;protectedSuccess=false;}
    public void close(){clearKeys();if(lastResponse!=null)Arrays.fill(lastResponse,(byte)0);lastRequest=null;lastResponse=null;lastId=-1;}
}
