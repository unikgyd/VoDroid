package io.vodroid.core;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import static io.vodroid.core.Bytes.*;

/** Algorithms actually selected from our IKE / CHILD_SA offer. */
public record IkeSuite(int encryptionBits,int prfId,int integrityId,int dhGroup) {
    public static final IkeSuite DEFAULT=new IkeSuite(128,5,12,14);
    public IkeSuite {
        if((encryptionBits!=128&&encryptionBits!=256)||(prfId!=0&&prfId!=2&&prfId!=5)||(integrityId!=2&&integrityId!=12)||(dhGroup!=0&&dhGroup!=2&&dhGroup!=14)||(prfId==0)!=(dhGroup==0))throw new IllegalArgumentException("Unsupported IKE suite");
    }
    public int encryptionLength(){return encryptionBits/8;}
    public int prfLength(){if(prfId==0)throw new IllegalStateException("ESP has no PRF");return prfId==2?20:32;}
    public int integrityLength(){return integrityId==2?20:32;}
    public int checksumLength(){return integrityId==2?12:16;}
    public byte[] prf(byte[] key,byte[] data)throws GeneralSecurityException{return Crypto.mac(prfId==2?"HmacSHA1":"HmacSHA256",key,data);}
    public byte[] expand(byte[] key,byte[] seed,int length)throws GeneralSecurityException{
        byte[] output=new byte[length],last=new byte[0];int offset=0;
        try{for(int counter=1;offset<length;counter++){
            if(counter>255)throw new GeneralSecurityException("PRF length");
            byte[] next=prf(key,cat(last,seed,b(counter)));Arrays.fill(last,(byte)0);last=next;
            int count=Math.min(last.length,length-offset);System.arraycopy(last,0,output,offset,count);offset+=count;
        }return output;}finally{Arrays.fill(last,(byte)0);}
    }
    public byte[] checksum(byte[] key,byte[] data)throws GeneralSecurityException{return cut(Crypto.mac(integrityId==2?"HmacSHA1":"HmacSHA256",key,data),0,checksumLength());}
    public int ikeKeyLength(){return prfLength()*3+integrityLength()*2+encryptionLength()*2;}
    public int childKeyLength(){return 2*(encryptionLength()+integrityLength());}
    public String description(){return "AES-"+encryptionBits+" / PRF-"+(prfId==2?"SHA1":"SHA256")+" / HMAC-"+(integrityId==2?"SHA1-96":"SHA256-128")+" / MODP"+dhGroup;}
}
