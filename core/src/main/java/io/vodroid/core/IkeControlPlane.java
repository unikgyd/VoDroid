package io.vodroid.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static io.vodroid.core.Bytes.*;
import static io.vodroid.core.IkeWire.*;

/** Single-reader post-authentication control plane. Rekeys never replace the IP interface. */
final class IkeControlPlane implements AutoCloseable {
    interface Sender {void send(List<byte[]> packets)throws Exception;}
    private enum Kind {DPD,CHILD,IKE,DELETE}
    private static final long SECOND=TimeUnit.SECONDS.toNanos(1),GRACE=60*SECOND;
    private static final class Pending implements AutoCloseable {
        final Kind kind;final long id;final int exchange;final List<byte[]> packets;
        final byte[] nonce,spi,oldSpi;final IkeSuite suite;final IkeDh dh;
        long sent;int sends=1;
        Pending(Kind kind,long id,int exchange,List<byte[]> packets,byte[] nonce,byte[] spi,byte[] oldSpi,IkeSuite suite,IkeDh dh,long now){this.kind=kind;this.id=id;this.exchange=exchange;this.packets=packets;this.nonce=nonce;this.spi=spi;this.oldSpi=oldSpi;this.suite=suite;this.dh=dh;sent=now;}
        public void close(){if(dh!=null)dh.close();if(nonce!=null)Arrays.fill(nonce,(byte)0);}
    }
    private final Sender sender;
    private final Consumer<String> log;
    private final IkePolicy policy;
    private final IkeTiming timing;
    private final String address;
    private final boolean fragmentation;
    final EspChildren children;
    private final List<IkeSa> sas=new ArrayList<>();
    private final Map<IkeSa,Pending> pending=new IdentityHashMap<>();
    private IkeSa current;
    private long lastAuthenticated,nextRekeyTry;
    private int retryGroup;
    private boolean closed;
    private int rekeys;
    private long lastRekey;
    IkeControlPlane(IkeSa initial,EspChildren children,boolean fragmentation,IkePolicy policy,String address,Sender sender,Consumer<String> log,long now){
        this(initial,children,fragmentation,policy,address,sender,log,now,IkeTiming.DEFAULT);
    }
    IkeControlPlane(IkeSa initial,EspChildren children,boolean fragmentation,IkePolicy policy,String address,Sender sender,Consumer<String> log,long now,IkeTiming timing){
        current=initial;this.children=children;this.fragmentation=fragmentation;this.policy=policy;this.timing=timing;this.address=address;this.sender=sender;this.log=log;sas.add(initial);lastAuthenticated=now;
    }
    synchronized int rekeys(){return rekeys;}synchronized long lastRekey(){return lastRekey;}
    synchronized IkeSuite suite(){return current.suite;}
    synchronized void authenticated(long now){lastAuthenticated=now;}
    private List<byte[]> seal(IkeSa sa,int exchange,long id,boolean response,List<Payload> payloads)throws Exception{
        return IkeEncrypted.seal(new Message(sa.i,sa.r,exchange,sa.flags(response),id,0,new byte[0]),payloads,sa.suite,sa.outEnc(),sa.outMac(),fragmentation);
    }
    synchronized void receive(byte[] wire,long now)throws Exception{
        if(closed)return;Message h;try{h=decode(wire);}catch(IOException bad){return;}
        IkeSa sa=null;for(IkeSa candidate:sas)if(candidate.matches(h)){sa=candidate;break;}
        if(sa==null||(h.exchange()!=36&&h.exchange()!=37))return;
        boolean response=(h.flags()&32)!=0;
        Pending task=pending.get(sa);
        if(response&&(task==null||task.id!=h.id()||task.exchange!=h.exchange()))return;
        if(!response&&h.id()!=sa.nextPeer&&h.id()!=sa.lastReplyId)return;
        IkeEncrypted.Decoded decoded;
        try{decoded=sa.assembly.accept(wire,sa.suite,sa.inEnc(),sa.inMac(),fragmentation,now);}catch(IOException invalid){return;}
        if(decoded==null)return;
        lastAuthenticated=now;
        if(response){pending.remove(sa);try{response(sa,task,decoded.payloads(),now);}finally{task.close();}return;}
        if(h.id()==sa.lastReplyId){if(h.exchange()==sa.lastReplyExchange)sender.send(sa.lastReply);return;}
        if(h.id()!=sa.nextPeer)return;
        if(h.exchange()==37)informational(sa,h,decoded.payloads(),now);
        else incomingRekey(sa,h,decoded.payloads(),now);
    }
    private void reply(IkeSa sa,Message request,List<Payload> ps)throws Exception{
        List<byte[]> wire=seal(sa,request.exchange(),request.id(),true,ps);
        sa.lastReply=wire;sa.lastReplyId=request.id();sa.lastReplyExchange=request.exchange();sa.nextPeer++;sender.send(wire);
    }
    private void error(IkeSa sa,Message h,int code,byte[] data)throws Exception{reply(sa,h,List.of(p(41,IkeWire.notify(code,data))));}
    private static byte[] nonce(List<Payload> ps)throws IOException{byte[] n=get(ps,40);if(n==null||n.length<16||n.length>256)throw new IOException("Invalid rekey nonce");return n;}
    private static int protocol(List<Payload> ps)throws IOException{byte[] sa=get(ps,33);if(sa==null||sa.length<8)throw new IOException("Missing rekey SA");return u(sa[5]);}
    private static int notifyError(List<Payload> ps)throws IOException{
        int error=0;for(Payload p:ps)if(p.type()==41){byte[] n=p.body();if(n.length<4||u(n[1])>n.length-4)throw new IOException("Malformed rekey notify");int code=u16(n,2);if(code<16384)error=code;}return error;
    }
    private boolean allowed(int group){return group==14&&policy!=IkePolicy.LEGACY||group==2&&policy!=IkePolicy.MODERN;}
    private static byte[] rekeySpi(List<Payload> ps)throws IOException{
        byte[] spi=null;for(Payload p:ps)if(p.type()==41&&p.body().length>=4&&u16(p.body(),2)==16393){byte[] n=p.body();if(spi!=null||n.length!=8||u(n[0])!=3||u(n[1])!=4)throw new IOException("Invalid REKEY_SA notify");spi=cut(n,4,4);}return spi;
    }
    private void incomingRekey(IkeSa sa,Message h,List<Payload> ps,long now)throws Exception{
        // Serialize rekeys. TEMPORARY_FAILURE plus randomized retry avoids two competing successors.
        Pending local=pending.get(sa);
        if(sa!=current||sas.size()>=4||local!=null&&(local.kind==Kind.CHILD||local.kind==Kind.IKE)){error(sa,h,43,new byte[0]);return;}
        try{
            int protocol=protocol(ps);boolean ike=protocol==1;if(!ike&&protocol!=3){error(sa,h,14,new byte[0]);return;}
            byte[] ni=nonce(ps),ke=get(ps,34),oldSpi=rekeySpi(ps);int group=ke==null?0:ke.length>=4?u16(ke,0):-1;
            if(ike&&(oldSpi!=null||get(ps,44)!=null||get(ps,45)!=null))throw new IOException("IKE rekey contains CHILD fields");
            if(ike&&group==0||group!=0&&!allowed(group)){error(sa,h,17,n16(current.suite.dhGroup()));return;}
            if(!ike&&(oldSpi==null||!Arrays.equals(oldSpi,children.active().outboundSpi()))){error(sa,h,44,new byte[0]);return;}
            IkeRekeyProposal.Choice selected;
            try{selected=IkeRekeyProposal.choose(get(ps,33),ike,group,ike?sa.suite:children.active().suite());}
            catch(IOException unsupported){error(sa,h,14,new byte[0]);return;}
            if(!ike){IkeSession.validateSelectors(get(ps,44),address,false);IkeSession.validateSelectors(get(ps,45),address,true);}
            byte[] nr=Crypto.random(32),spi=Crypto.random(ike?8:4),dh=new byte[0];IkeSa successor=null;Esp child=null;
            try(IkeDh exchange=group==0?null:new IkeDh(group)){
                if(exchange!=null)dh=exchange.shared(ke);
                List<Payload> answer=new ArrayList<>(List.of(p(33,IkeRekeyProposal.encode(selected,ike,spi)),p(40,nr)));
                if(exchange!=null)answer.add(p(34,exchange.payload()));
                if(ike)successor=IkeSa.rekey(sa,selected.spi(),spi,selected.suite(),false,dh,ni,nr,now);
                else {answer.add(p(44,get(ps,44)));answer.add(p(45,get(ps,45)));child=child(sa,spi,selected.spi(),selected.suite(),dh,ni,nr,false);}
                try{reply(sa,h,answer);}catch(Exception sendFailure){if(successor!=null)successor.close();if(child!=null)child.close();throw sendFailure;}
                if(ike)install(sa,successor,now);else{children.install(child,now);changed("CHILD_SA",now);}
            }finally{Arrays.fill(dh,(byte)0);Arrays.fill(nr,(byte)0);}
        }catch(IOException invalid){if(sa.nextPeer!=h.id())throw invalid;error(sa,h,7,new byte[0]);}
    }
    private static Esp child(IkeSa sa,byte[] localSpi,byte[] remoteSpi,IkeSuite suite,byte[] dh,byte[] ni,byte[] nr,boolean localInitiator)throws Exception{
        byte[] keys=sa.suite.expand(sa.d,cat(dh,ni,nr),suite.childKeyLength());
        try{if(!localInitiator){int half=keys.length/2;byte[] swap=cat(cut(keys,half,half),cut(keys,0,half));Arrays.fill(keys,(byte)0);keys=swap;}return new Esp(localSpi,remoteSpi,keys,suite);}finally{Arrays.fill(keys,(byte)0);}
    }
    private void install(IkeSa old,IkeSa next,long now){
        old.retireAt=now+GRACE;current=next;sas.add(next);
        Pending task=pending.remove(old);if(task!=null)task.close();changed("IKE_SA",now);
    }
    private void changed(String name,long now){rekeys++;lastRekey=now;nextRekeyTry=0;log.accept(name+" 换钥完成");}
    private void response(IkeSa sa,Pending task,List<Payload> ps,long now)throws Exception{
        int error=notifyError(ps);
        if(error!=0){
            if((task.kind==Kind.IKE||task.kind==Kind.CHILD)&&(error==43||error==14||error==17)){
                if(error==17&&task.kind==Kind.IKE)for(Payload p:ps)if(p.type()==41&&p.body().length==6&&u16(p.body(),2)==17&&p.body()[0]==0&&p.body()[1]==0){int group=u16(p.body(),4);if(allowed(group))retryGroup=group;}
                nextRekeyTry=now+(10+Crypto.RANDOM.nextInt(21))*SECOND;log.accept("ePDG 暂未接受换钥 · 稍后重试");return;
            }throw new IOException("ePDG control rejected: "+error);
        }
        if(task.kind==Kind.DPD){if(!ps.isEmpty())throw new IOException("Unexpected liveness response");log.accept("ePDG 存活确认");return;}
        if(task.kind==Kind.DELETE)return;
        boolean ike=task.kind==Kind.IKE;int group=task.dh==null?0:task.dh.group;
        IkeRekeyProposal.Choice selected=IkeRekeyProposal.selected(get(ps,33),ike,group,task.suite);
        byte[] nr=nonce(ps),dh=task.dh==null?new byte[0]:task.dh.shared(get(ps,34));
        try{
            if(ike){if(get(ps,44)!=null||get(ps,45)!=null)throw new IOException("IKE rekey response contains selectors");IkeSa successor=IkeSa.rekey(sa,task.spi,selected.spi(),task.suite,true,dh,task.nonce,nr,now);install(sa,successor,now);startDelete(sa,true,null,now);}
            else {if(task.dh==null&&get(ps,34)!=null)throw new IOException("Unrequested CHILD PFS");IkeSession.validateSelectors(get(ps,44),address,true);IkeSession.validateSelectors(get(ps,45),address,false);children.install(child(sa,task.spi,selected.spi(),task.suite,dh,task.nonce,nr,true),now);changed("CHILD_SA",now);startDelete(sa,false,task.oldSpi,now);}
        }finally{Arrays.fill(dh,(byte)0);}
    }
    private void informational(IkeSa sa,Message h,List<Payload> ps,long now)throws Exception{
        List<Payload> answer=new ArrayList<>();boolean deleteIke=false,deleteActive=false;
        try{
            for(Payload p:ps)if(p.type()==42){byte[] d=p.body();if(d.length<4)throw new IOException("Invalid DELETE");int protocol=u(d[0]),size=u(d[1]),count=u16(d,2);
                if(protocol==1){if(size!=0||count!=0||d.length!=4)throw new IOException("Invalid IKE DELETE");deleteIke=true;}
                else if(protocol==3){if(size!=4||count>64||d.length!=4+4*count)throw new IOException("Invalid ESP DELETE");byte[] spis=new byte[0];int found=0;
                    for(int n=0;n<count;n++){byte[] spi=cut(d,4+n*4,4);if(Arrays.equals(children.active().outboundSpi(),spi)){deleteActive=true;spis=cat(spis,children.active().inboundSpi());found++;continue;}byte[] ours=children.delete(spi);if(ours!=null){spis=cat(spis,ours);found++;}}
                    if(found>0)answer.add(p(42,cat(b(3,4),n16(found),spis)));
                }else throw new IOException("Unsupported DELETE protocol");
            }
        }catch(IOException invalid){error(sa,h,7,new byte[0]);return;}
        reply(sa,h,answer);
        if(deleteActive||deleteIke&&sa==current)throw new IOException("ePDG deleted the active security association");
        if(deleteIke){sa.retireAt=now+GRACE;Pending task=pending.remove(sa);if(task!=null)task.close();}
    }
    private void start(IkeSa sa,Kind kind,int exchange,List<Payload> ps,byte[] nonce,byte[] spi,byte[] oldSpi,IkeSuite suite,IkeDh dh,long now)throws Exception{
        if(sa.nextLocal>=0xffffffffL)throw new IOException("IKE message ID exhausted");long id=sa.nextLocal++;
        List<byte[]> packets=seal(sa,exchange,id,false,ps);Pending task=new Pending(kind,id,exchange,packets,nonce,spi,oldSpi,suite,dh,now);pending.put(sa,task);
        try{sender.send(packets);}catch(Exception failed){pending.remove(sa);task.close();throw failed;}
    }
    private void startDelete(IkeSa sa,boolean ike,byte[] spi,long now)throws Exception{start(sa,Kind.DELETE,37,List.of(p(42,ike?b(1,0,0,0):cat(b(3,4,0,1),spi))),null,null,null,null,null,now);}
    synchronized boolean rekey(boolean ike,long now)throws Exception{
        if(closed||pending.containsKey(current)||sas.size()>=4||now<nextRekeyTry)return false;
        IkeSa sa=current;IkeSuite suite=ike?new IkeSuite(sa.suite.encryptionBits(),sa.suite.prfId(),sa.suite.integrityId(),retryGroup==0?sa.suite.dhGroup():retryGroup):children.active().suite();byte[] spi=Crypto.random(ike?8:4),ni=Crypto.random(32),oldSpi=ike?null:children.active().inboundSpi();
        IkeDh dh=ike||timing.childPfs()?new IkeDh(ike?suite.dhGroup():sa.suite.dhGroup()):null;
        List<Payload> ps=new ArrayList<>(List.of(p(33,IkeRekeyProposal.encode(new IkeRekeyProposal.Choice(1,spi,suite,dh==null?0:dh.group),ike,spi)),p(40,ni)));
        if(dh!=null)ps.add(p(34,dh.payload()));if(!ike){ps.add(p(41,cat(b(3,4),n16(16393),oldSpi)));ps.add(p(44,trafficSelector()));ps.add(p(45,trafficSelector()));}
        try{start(sa,ike?Kind.IKE:Kind.CHILD,36,ps,ni,spi,oldSpi,suite,dh,now);return true;}catch(Exception error){if(dh!=null)dh.close();Arrays.fill(ni,(byte)0);throw error;}
    }
    synchronized void tick(long now)throws Exception{
        if(closed)return;children.expire(now);
        for(Iterator<IkeSa> it=sas.iterator();it.hasNext();){IkeSa sa=it.next();if(sa!=current&&now>=sa.retireAt){Pending p=pending.remove(sa);if(p!=null)p.close();sa.close();it.remove();}}
        for(Map.Entry<IkeSa,Pending> entry:new ArrayList<>(pending.entrySet())){
            Pending p=entry.getValue();if(now-p.sent<5*SECOND)continue;
            if(p.sends>=4){pending.remove(entry.getKey());p.close();if(p.kind!=Kind.DELETE||entry.getKey()==current)throw new SocketTimeoutException("IKE control timeout; reconnect required");continue;}
            sender.send(p.packets);p.sent=now;p.sends++;
        }
        if(now-current.created>=TimeUnit.SECONDS.toNanos(timing.ikeHard()))throw new IOException("IKE hard lifetime reached");
        if(children.expired(now,timing.childHard()))throw new IOException("CHILD_SA hard lifetime reached");
        if(!pending.containsKey(current)){
            if(now>=nextRekeyTry&&now-current.created>=TimeUnit.SECONDS.toNanos(timing.ikeSoft())){rekey(true,now);return;}
            if(now>=nextRekeyTry&&children.due(now,timing.childSoft())){rekey(false,now);return;}
            if(now-lastAuthenticated>=30*SECOND){start(current,Kind.DPD,37,List.of(),null,null,null,null,null,now);log.accept("ePDG 存活探测");}
        }
    }
    public synchronized void close(){if(closed)return;closed=true;for(Pending p:pending.values())p.close();pending.clear();for(IkeSa sa:sas)sa.close();sas.clear();children.close();}
}
