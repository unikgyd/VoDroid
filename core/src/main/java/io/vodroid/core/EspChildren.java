package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.vodroid.core.Bytes.*;

/** Switch outbound atomically; keep old inbound keys for reordered packets. */
final class EspChildren implements AutoCloseable {
    private record Entry(Esp esp,long expires){}
    private final List<Entry> entries=new ArrayList<>();
    private Esp active;
    private long created;
    EspChildren(Esp initial,long now){active=initial;created=now;entries.add(new Entry(initial,Long.MAX_VALUE));}
    synchronized Esp active(){return active;}
    synchronized boolean due(long now,int seconds){return active.needsRekey()||now-created>=TimeUnit.SECONDS.toNanos(seconds);}
    synchronized boolean expired(long now,int seconds){return now-created>=TimeUnit.SECONDS.toNanos(seconds);}
    synchronized void install(Esp next,long now){
        for(int n=0;n<entries.size();n++)if(entries.get(n).esp==active)entries.set(n,new Entry(active,now+TimeUnit.SECONDS.toNanos(30)));
        active=next;created=now;entries.add(new Entry(next,Long.MAX_VALUE));expire(now);
        while(entries.size()>4){Entry e=entries.remove(0);e.esp.close();}
    }
    synchronized byte[] encrypt(byte[] ip)throws Exception{if(active==null)throw new IOException("ESP closed");return active.encrypt(ip);}
    synchronized byte[] decrypt(byte[] wire,long now)throws Exception{
        expire(now);if(wire.length<4)throw new IOException("Truncated ESP");
        for(Entry e:entries)if(Arrays.equals(e.esp.inboundSpi(),cut(wire,0,4)))return e.esp.decrypt(wire);
        throw new IOException("Unknown ESP SPI");
    }
    synchronized byte[] delete(byte[] peerInbound)throws IOException{
        for(Iterator<Entry> it=entries.iterator();it.hasNext();){Entry e=it.next();if(Arrays.equals(e.esp.outboundSpi(),peerInbound)){if(e.esp==active)throw new IOException("ePDG deleted the active CHILD_SA");byte[] reply=e.esp.inboundSpi();it.remove();e.esp.close();return reply;}}return null;
    }
    synchronized void expire(long now){for(Iterator<Entry> it=entries.iterator();it.hasNext();){Entry e=it.next();if(now>=e.expires){e.esp.close();it.remove();}}}
    public synchronized void close(){for(Entry e:entries)e.esp.close();entries.clear();active=null;}
}
