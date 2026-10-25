package io.vodroid.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Bytes {
    private Bytes() {}
    public static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        if ((s.length() & 1) != 0 || !s.matches("[0-9a-fA-F]*")) throw new IllegalArgumentException("Invalid hex");
        byte[] r = new byte[s.length()/2];
        for (int i=0;i<r.length;i++) r[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return r;
    }
    public static String hex(byte[] a) { StringBuilder s=new StringBuilder(); for(byte b:a)s.append(String.format("%02X",b&255)); return s.toString(); }
    public static byte[] cat(byte[]... a) { ByteArrayOutputStream b=new ByteArrayOutputStream(); for(byte[] v:a)b.write(v,0,v.length); return b.toByteArray(); }
    public static byte[] b(int... v) { byte[] a=new byte[v.length]; for(int i=0;i<v.length;i++)a[i]=(byte)v[i]; return a; }
    public static int u(byte b) { return b&255; }
    public static int u16(byte[] a,int p) { range(a,p,2); return (u(a[p])<<8)|u(a[p+1]); }
    public static long u32(byte[] a,int p) { range(a,p,4); return Integer.toUnsignedLong(ByteBuffer.wrap(a,p,4).getInt()); }
    public static byte[] n16(int n) { return b(n>>8,n); }
    public static byte[] n32(long n) { return ByteBuffer.allocate(4).putInt((int)n).array(); }
    public static byte[] cut(byte[] a,int p,int n) { range(a,p,n); return Arrays.copyOfRange(a,p,p+n); }
    public static void range(byte[] a,int p,int n) { if(p<0||n<0||p>a.length-n)throw new IllegalArgumentException("Truncated protocol data"); }
    public static String bcd(byte[] a) { StringBuilder s=new StringBuilder(); for(byte b:a){int l=b&15,h=(b>>>4)&15;if(l<10)s.append(l);if(h<10)s.append(h);}return s.toString(); }
    public static byte[] bcd(String s) { if(!s.matches("[0-9]+"))throw new IllegalArgumentException("Invalid digits");byte[] b=new byte[(s.length()+1)/2];for(int i=0;i<b.length;i++){int p=2*i;b[i]=(byte)((s.charAt(p)-'0')|((p+1<s.length()?s.charAt(p+1)-'0':15)<<4));}return b; }
    public static ByteBuffer le(byte[] b) {return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
}
