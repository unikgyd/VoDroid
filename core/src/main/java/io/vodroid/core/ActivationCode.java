package io.vodroid.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Credential-bearing input. Never include its contents in diagnostics. */
public record ActivationCode(String address, String matchingId, String payload) {
    public static ActivationCode parse(String input) {
        if(input==null||input.length()>8192)throw invalid();
        String value=input.trim();
        if(value.regionMatches(true,0,"https://",0,8)||value.regionMatches(true,0,"http://",0,7)){
            try{
                URI uri=URI.create(value);String query=uri.getRawQuery();String code=null;
                if(query!=null)for(String entry:query.split("&")){
                    String[] pair=entry.split("=",2);String key=decode(pair[0]);
                    if(pair.length==2&&(key.equals("carddata")||key.equals("activation_code")||key.equals("activationCode"))){if(code!=null)throw invalid();code=decode(pair[1]);}
                }
                if(code==null)throw invalid();value=code;
            }catch(IllegalArgumentException e){throw invalid();}
        }
        if(value.regionMatches(true,0,"lpa:",0,4))value=value.substring(4);
        if(value.contains("%"))value=decode(value.replace("+","%2B"));
        if(value.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw invalid();
        String[] parts=value.split("\\$",-1);
        if(parts.length<3||parts.length>5||!parts[0].equals("1")||parts[1].isEmpty()||parts[1].length()>253)throw invalid();
        // The SM-DP+ field is an authority, not an arbitrary URL or path.
        try{URI authority=URI.create("https://"+parts[1]);if(authority.getHost()==null||authority.getRawUserInfo()!=null||authority.getRawQuery()!=null||authority.getRawFragment()!=null||!authority.getPath().isEmpty())throw invalid();}catch(IllegalArgumentException e){throw invalid();}
        if(parts.length>=4&&!parts[3].isEmpty()&&!parts[3].matches("[0-9]+(?:\\.[0-9]+)+"))throw invalid();
        if(parts.length==5&&!parts[4].matches("[01]"))throw invalid();
        return new ActivationCode(parts[1],parts[2],value);
    }
    private static String decode(String value){try{return URLDecoder.decode(value,StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("激活码或安装链接格式无效");}
    public String canonical(){return "LPA:"+payload;}
    @Override public String toString(){return "ActivationCode[redacted]";}
}
