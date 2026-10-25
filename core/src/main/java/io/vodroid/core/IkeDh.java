package io.vodroid.core;

import java.io.IOException;
import java.math.BigInteger;
import static io.vodroid.core.Bytes.*;

/** A fresh MODP exchange for each rekey, including public-value validation. */
final class IkeDh implements AutoCloseable {
    final int group,length;
    private BigInteger secret;
    private final BigInteger prime;
    IkeDh(int group){this.group=group;prime=IkeSession.dhPrime(group);length=prime.bitLength()/8;secret=new BigInteger(256,Crypto.RANDOM).add(BigInteger.TWO);}
    byte[] payload(){return cat(n16(group),b(0,0),Crypto.fixed(BigInteger.TWO.modPow(secret,prime),length));}
    byte[] shared(byte[] ke)throws IOException{
        if(ke==null||ke.length!=length+4||u16(ke,0)!=group)throw new IOException("Invalid rekey DH group or length");
        BigInteger peer=new BigInteger(1,cut(ke,4,length));if(peer.compareTo(BigInteger.TWO)<0||peer.compareTo(prime.subtract(BigInteger.TWO))>0)throw new IOException("Invalid rekey DH public value");
        return Crypto.fixed(peer.modPow(secret,prime),length);
    }
    public void close(){secret=null;}
}
