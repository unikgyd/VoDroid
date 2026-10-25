package io.vodroid.core;

import java.util.*;
import static io.vodroid.core.Bytes.*;

/** One IKE SA, including direction, independent message windows and reassembly. */
final class IkeSa implements AutoCloseable {
    final byte[] i,r,d,ai,ar,ei,er;
    final IkeSuite suite;
    final boolean localInitiator;
    final long created;
    long nextLocal,nextPeer,retireAt=Long.MAX_VALUE,lastReplyId=-1;
    int lastReplyExchange;
    List<byte[]> lastReply=List.of();
    final IkeEncrypted.Reassembly assembly=new IkeEncrypted.Reassembly();
    IkeSa(byte[] i,byte[] r,IkeSuite suite,boolean initiator,byte[] d,byte[] ai,byte[] ar,byte[] ei,byte[] er,long now){
        this.i=i.clone();this.r=r.clone();this.suite=suite;localInitiator=initiator;this.d=d.clone();this.ai=ai.clone();this.ar=ar.clone();this.ei=ei.clone();this.er=er.clone();created=now;
    }
    byte[] outEnc(){return localInitiator?ei:er;}byte[] inEnc(){return localInitiator?er:ei;}
    byte[] outMac(){return localInitiator?ai:ar;}byte[] inMac(){return localInitiator?ar:ai;}
    int flags(boolean response){return (localInitiator?8:0)|(response?32:0);}
    boolean matches(IkeWire.Message h){return Arrays.equals(i,h.initiator())&&Arrays.equals(r,h.responder())&&((h.flags()&8)!=0)!=localInitiator;}
    static IkeSa rekey(IkeSa old,byte[] i,byte[] r,IkeSuite suite,boolean localInitiator,byte[] dh,byte[] ni,byte[] nr,long now)throws Exception{
        // RFC 7296 2.18: SKEYSEED uses the OLD PRF; expansion uses the NEW PRF.
        byte[] seed=old.suite.prf(old.d,cat(dh,ni,nr)),keys=suite.expand(seed,cat(ni,nr,i,r),suite.ikeKeyLength());
        try{int p=suite.prfLength(),m=suite.integrityLength(),e=suite.encryptionLength();return new IkeSa(i,r,suite,localInitiator,cut(keys,0,p),cut(keys,p,m),cut(keys,p+m,m),cut(keys,p+2*m,e),cut(keys,p+2*m+e,e),now);}
        finally{Arrays.fill(seed,(byte)0);Arrays.fill(keys,(byte)0);}
    }
    public void close(){assembly.close();for(byte[] key:new byte[][]{d,ai,ar,ei,er})Arrays.fill(key,(byte)0);lastReply=List.of();}
}
