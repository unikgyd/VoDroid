package io.vodroid.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.vodroid.core.Bytes.*;

public final class SipMessage {
    public final String start;
    public final Map<String,List<String>> headers;
    public final byte[] body;
    public SipMessage(String s,Map<String,List<String>> h,byte[] b){start=s;headers=h;body=b;}
    public String header(String k){List<String> v=headers.get(k.toLowerCase(Locale.ROOT));return v==null?"":v.get(0);}
    public List<String> all(String k){return headers.getOrDefault(k.toLowerCase(Locale.ROOT),List.of());}
    public int code(){return start.startsWith("SIP/2.0 ")?Integer.parseInt(start.substring(8,11)):0;}
    public String method(){return start.split(" ",3)[0];}
    public String text(){return new String(body,StandardCharsets.UTF_8);}
    public static SipMessage parse(byte[] packet) throws IOException {
        int split=-1;for(int i=0;i+3<packet.length;i++)if(packet[i]==13&&packet[i+1]==10&&packet[i+2]==13&&packet[i+3]==10){split=i;break;}
        if(split<0||split>65536)throw new IOException("Malformed SIP headers");String[] lines=new String(packet,0,split,StandardCharsets.UTF_8).split("\r\n");if(lines.length==0)throw new IOException("Empty SIP message");Map<String,List<String>> h=new LinkedHashMap<>();String last=null;
        for(int i=1;i<lines.length;i++){String l=lines[i];if(l.startsWith(" ")||l.startsWith("\t")){if(last==null)throw new IOException("Invalid SIP continuation");List<String> vals=h.get(last);vals.set(vals.size()-1,vals.get(vals.size()-1)+" "+l.trim());continue;}int c=l.indexOf(':');if(c<1)throw new IOException("Invalid SIP header");last=l.substring(0,c).toLowerCase(Locale.ROOT);last=switch(last){case "v"->"via";case "f"->"from";case "t"->"to";case "i"->"call-id";case "l"->"content-length";case "c"->"content-type";case "m"->"contact";default->last;};h.computeIfAbsent(last,k->new ArrayList<>()).add(l.substring(c+1).trim());}
        int n=packet.length-split-4;if(h.containsKey("content-length")){try{n=Integer.parseInt(h.get("content-length").get(0));}catch(NumberFormatException e){throw new IOException("Invalid SIP content length");}}if(n<0||split+4+n!=packet.length)throw new IOException("SIP content length mismatch");return new SipMessage(lines[0],h,cut(packet,split+4,n));
    }
    public static byte[] encode(String start,List<String> headers,byte[] body){for(String h:headers)if(h.contains("\r")||h.contains("\n"))throw new IllegalArgumentException("SIP header injection");String head=start+"\r\n"+(headers.isEmpty()?"":String.join("\r\n",headers)+"\r\n")+"Content-Length: "+body.length+"\r\n\r\n";return cat(head.getBytes(StandardCharsets.UTF_8),body);}
    public static Map<String,String> parameters(String input){
        Map<String,String> out=new LinkedHashMap<>();input=input.replaceFirst("(?i)^Digest\\s+","");int p=0;
        while(p<input.length()){while(p<input.length()&&(input.charAt(p)==','||input.charAt(p)==';'||Character.isWhitespace(input.charAt(p))))p++;int eq=input.indexOf('=',p);if(eq<0)break;String name=input.substring(p,eq).trim().toLowerCase(Locale.ROOT);p=eq+1;String value;
            if(p<input.length()&&input.charAt(p)=='"'){StringBuilder b=new StringBuilder();p++;while(p<input.length()){char c=input.charAt(p++);if(c=='"')break;if(c=='\\'&&p<input.length())c=input.charAt(p++);b.append(c);}value=b.toString();}else{int end=p;while(end<input.length()&&input.charAt(end)!=','&&input.charAt(end)!=';')end++;value=input.substring(p,end).trim();p=end;}out.put(name,value);
        }return out;
    }
    public static String tag(String h){for(String p:h.split(";"))if(p.trim().startsWith("tag="))return p.trim().substring(4);return "";}
    public static String uri(String h){int a=h.indexOf('<'),b=h.indexOf('>');return a>=0&&b>a?h.substring(a+1,b):h.split(";",2)[0].trim();}
}
