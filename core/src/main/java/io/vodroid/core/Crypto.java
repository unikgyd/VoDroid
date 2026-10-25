package io.vodroid.core;

import java.math.BigInteger;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import static io.vodroid.core.Bytes.*;

public final class Crypto {
    public static final SecureRandom RANDOM = new SecureRandom();
    private Crypto(){}
    public static byte[] random(int n){byte[] b=new byte[n];RANDOM.nextBytes(b);return b;}
    public static byte[] digest(String a,byte[] b) throws GeneralSecurityException {return MessageDigest.getInstance(a).digest(b);}
    public static byte[] mac(String a,byte[] k,byte[] b) throws GeneralSecurityException {Mac m=Mac.getInstance(a);m.init(new SecretKeySpec(k,a));return m.doFinal(b);}
    public static byte[] prf(byte[] k,byte[] b) throws GeneralSecurityException{return mac("HmacSHA256",k,b);}
    public static byte[] expand(byte[] key,byte[] seed,int n) throws GeneralSecurityException{
        byte[] out=new byte[0],last=new byte[0];for(int i=1;out.length<n;i++){if(i>255)throw new GeneralSecurityException("PRF length");last=prf(key,cat(last,seed,b(i)));out=cat(out,last);}return cut(out,0,n);
    }
    public static byte[] aes(boolean encrypt,byte[] key,byte[] iv,byte[] data) throws GeneralSecurityException{
        Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(data);
    }
    public static byte[] fixed(BigInteger v,int n){byte[] a=v.toByteArray();if(a.length>n)return cut(a,a.length-n,n);byte[] b=new byte[n];System.arraycopy(a,0,b,n-a.length,a.length);return b;}
    /** 3GPP TS 33.402 / RFC 5448: CK' || IK', then AKA' PRF+ hierarchy. */
    public static byte[] akaPrimeBase(byte[] ck,byte[] ik,byte[] autn,byte[] network)throws GeneralSecurityException{
        if(ck.length!=16||ik.length!=16||autn.length!=16||network.length==0||network.length>65535)throw new IllegalArgumentException("AKA prime KDF inputs");
        byte[] key=cat(ck,ik);
        try{return mac("HmacSHA256",key,cat(b(0x20),network,n16(network.length),cut(autn,0,6),n16(6)));}finally{Arrays.fill(key,(byte)0);}
    }
    public static byte[] akaPrimeKeys(byte[] ck,byte[] ik,byte[] autn,byte[] network,byte[] identity)throws GeneralSecurityException{
        byte[] base=akaPrimeBase(ck,ik,autn,network),key=cat(cut(base,16,16),cut(base,0,16));
        try{return expand(key,cat("EAP-AKA'".getBytes(java.nio.charset.StandardCharsets.US_ASCII),identity),208);}finally{Arrays.fill(base,(byte)0);Arrays.fill(key,(byte)0);}
    }
    // RFC 4187 §7: FIPS 186-2 PRF uses the SHA-1 compression function, without SHA padding.
    public static byte[] akaPrf(byte[] mk){
        if(mk.length!=20)throw new IllegalArgumentException("AKA MK length");
        BigInteger x=new BigInteger(1,mk),mod=BigInteger.ONE.shiftLeft(160);byte[] out=new byte[0];
        for(int i=0;i<8;i++){byte[] block=new byte[64];System.arraycopy(fixed(x,20),0,block,0,20);byte[] w=sha1Compress(block);out=cat(out,w);x=x.add(new BigInteger(1,w)).add(BigInteger.ONE).mod(mod);}return out;
    }
    private static byte[] sha1Compress(byte[] block){
        int[] w=new int[80];for(int i=0;i<16;i++)w[i]=(int)u32(block,i*4);for(int i=16;i<80;i++)w[i]=Integer.rotateLeft(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
        int a=0x67452301,b=0xefcdab89,c=0x98badcfe,d=0x10325476,e=0xc3d2e1f0;
        for(int i=0;i<80;i++){int f,k;if(i<20){f=(b&c)|(~b&d);k=0x5a827999;}else if(i<40){f=b^c^d;k=0x6ed9eba1;}else if(i<60){f=(b&c)|(b&d)|(c&d);k=0x8f1bbcdc;}else{f=b^c^d;k=0xca62c1d6;}int t=Integer.rotateLeft(a,5)+f+e+k+w[i];e=d;d=c;c=Integer.rotateLeft(b,30);b=a;a=t;}
        return cat(n32(a+0x67452301),n32(b+0xefcdab89),n32(c+0x98badcfe),n32(d+0x10325476),n32(e+0xc3d2e1f0));
    }
}
