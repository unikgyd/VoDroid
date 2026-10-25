package io.vodroid.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.vodroid.core.Bytes.*;

public final class SipDigest {
    public record Result(String authorization,byte[] ck,byte[] ik,String auts) implements AutoCloseable{public void close(){Arrays.fill(ck,(byte)0);Arrays.fill(ik,(byte)0);}}
    private static String md5(byte[] a) throws Exception{return hex(Crypto.digest("MD5",a)).toLowerCase(Locale.ROOT);}
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String quote(String s){if(s.contains("\r")||s.contains("\n"))throw new IllegalArgumentException("Invalid digest value");return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    public static Result answer(String challenge,String user,String method,String uri,byte[] body,SimCard card) throws Exception{
        Map<String,String> p=SipMessage.parameters(challenge);String algorithm=p.getOrDefault("algorithm","");if(!algorithm.equalsIgnoreCase("AKAv1-MD5"))throw new IOException("Unsupported IMS digest algorithm: "+algorithm);
        String nonce=p.getOrDefault("nonce",""),realm=p.getOrDefault("realm","");byte[] decoded;try{decoded=Base64.getDecoder().decode(nonce);}catch(IllegalArgumentException e){throw new IOException("Invalid AKA nonce");}if(decoded.length<32)throw new IOException("Truncated IMS AKA nonce");
        try(SimCard.Aka aka=card.authenticate(cut(decoded,0,16),cut(decoded,16,16))){
            String prefix="Digest username="+quote(user)+", realm="+quote(realm)+", nonce="+quote(nonce)+", uri="+quote(uri)+", algorithm=AKAv1-MD5";
            if(!aka.synchronizedOk()){String auts=Base64.getEncoder().encodeToString(aka.auts);return new Result(prefix+", response=\"\", auts="+quote(auts),new byte[0],new byte[0],auts);}
            String qop="";List<String> qops=Arrays.asList(p.getOrDefault("qop","").toLowerCase(Locale.ROOT).split("\\s*,\\s*"));if(qops.contains("auth"))qop="auth";else if(qops.contains("auth-int"))qop="auth-int";else if(!p.getOrDefault("qop","").isEmpty())throw new IOException("Unsupported SIP digest qop");
            String ha1=md5(cat(utf(user+":"+realm+":"),aka.res)),ha2=md5(utf(method+":"+uri+(qop.equals("auth-int")?":"+md5(body):""))),cnonce=hex(Crypto.random(16)).toLowerCase(Locale.ROOT),nc="00000001";
            String response=md5(utf(ha1+":"+nonce+":"+(qop.isEmpty()?"":nc+":"+cnonce+":"+qop+":")+ha2));String authorization=prefix+", response="+quote(response);
            if(!qop.isEmpty())authorization+=", qop="+qop+", nc="+nc+", cnonce="+quote(cnonce);if(p.containsKey("opaque"))authorization+=", opaque="+quote(p.get("opaque"));return new Result(authorization,aka.ck.clone(),aka.ik.clone(),"");
        }finally{Arrays.fill(decoded,(byte)0);}
    }
}
