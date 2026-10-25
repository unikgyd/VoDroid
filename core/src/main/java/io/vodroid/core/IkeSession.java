package io.vodroid.core;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import static io.vodroid.core.Bytes.*;
import static io.vodroid.core.IkeWire.*;

/** Independent RFC 7296 initiator with validated IKE / ESP algorithm negotiation. */
public final class IkeSession implements AutoCloseable {
    public interface Protector {void protect(DatagramSocket socket) throws Exception;}
    public interface Resolver {InetAddress[] resolve(String host) throws Exception;}
    public record Tunnel(String address,int prefix,List<String> pcscf,String epdg,Esp esp){}
    private static final byte[] PAD="Key Pad for IKEv2".getBytes(StandardCharsets.US_ASCII);
    private static final BigInteger P=new BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",16);
    private static final BigInteger P1024=new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381FFFFFFFFFFFFFFFF",16);
    private final Protector protector;
    private final Consumer<String> log;
    private final Resolver resolver;
    private final IkePolicy policy;
    private final CarrierProfile carrierProfile;
    private EapPolicy eapPolicy=EapPolicy.AUTO;
    private volatile String eapMethod="—";
    public void setEapPolicy(EapPolicy policy){if(authenticationStarted)throw new IllegalStateException("Authentication already started");eapPolicy=Objects.requireNonNull(policy);}
    public record Health(String eap,String suite,boolean fragmentation,int rekeys,long lastRekeyNanos){}
    public Health health(){IkeControlPlane plane=control;return new Health(eapMethod,plane==null?suite.description():plane.suite().description(),fragmentation,plane==null?0:plane.rekeys(),plane==null?0:plane.lastRekey());}
    public boolean requestRekey(boolean ike)throws Exception{IkeControlPlane plane=control;if(closed||plane==null)throw new IOException("Tunnel not established");return plane.rekey(ike,System.nanoTime());}
    private final int initialPort,nattPort,retryBaseMillis;
    private DatagramSocket socket;
    private final byte[] spiI=Crypto.random(8),ni=Crypto.random(32),childSpi=Crypto.random(4);
    private byte[] spiR=new byte[8],nr,skd,ai,ar,ei,er,pi,pr,initI,initR,idI,idR;
    private long nextId;
    private volatile boolean closed;
    private boolean natt;
    private boolean authenticationStarted;
    private boolean fragmentation;
    private long setupDeadline;
    private Esp esp;
    private volatile IkeControlPlane control;
    static BigInteger dhPrime(int group){return switch(group){case 14->P;case 2->P1024;default->throw new IllegalArgumentException("Unsupported MODP group");};}
    private IkeSuite suite=IkeSuite.DEFAULT;
    private volatile ImsIpsec imsIpsec;
    public void setImsIpsec(ImsIpsec security){imsIpsec=security;}
    public IkeSession(Protector p,Consumer<String> l){this(p,l,InetAddress::getAllByName);}
    public IkeSession(Protector p,Consumer<String> l,Resolver r){this(p,l,r,IkePolicy.AUTO);}
    public IkeSession(Protector p,Consumer<String> l,IkePolicy policy){this(p,l,InetAddress::getAllByName,policy);}
    public IkeSession(Protector p,Consumer<String> l,Resolver r,IkePolicy policy){this(p,l,r,policy,CarrierProfile.STANDARD);}
    public IkeSession(Protector p,Consumer<String> l,IkePolicy policy,CarrierProfile profile){this(p,l,InetAddress::getAllByName,policy,profile);}
    public IkeSession(Protector p,Consumer<String> l,Resolver r,IkePolicy policy,CarrierProfile profile){this(p,l,r,policy,profile,500,4500,1000);}
    IkeSession(Protector p,Consumer<String> l,Resolver r,IkePolicy policy,CarrierProfile profile,int initialPort,int nattPort,int retryBaseMillis){
        protector=p;log=l;resolver=r;this.policy=Objects.requireNonNull(policy);carrierProfile=Objects.requireNonNull(profile);
        this.initialPort=initialPort;this.nattPort=nattPort;this.retryBaseMillis=retryBaseMillis;
    }
    static InetAddress selectEndpoint(InetAddress[] addresses) throws IOException {
        for(InetAddress address:addresses)if(address instanceof Inet4Address)return address;
        for(InetAddress address:addresses)if(address instanceof Inet6Address)return address;
        throw new IOException("ePDG DNS returned no IP endpoint");
    }
    public Tunnel connect(String host,SimCard.Identity identity,SimCard card) throws Exception {
        CarrierProfile profile=carrierProfile;
        log.accept("运营商配置 "+profile.id()+" · IKE "+policy);
        setupDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(75);
        List<Exception> failures=new ArrayList<>();int tried=0;
        for(String candidate:EpdgDiscovery.hosts(host,identity,profile)){
            if(closed)throw new IOException("IKE session closed");
            if(tried>=8||System.nanoTime()>=setupDeadline)break;
            log.accept("解析 ePDG "+candidate);
            List<InetAddress> addresses;
            try{addresses=EpdgDiscovery.addresses(resolver.resolve(candidate));}
            catch(UnknownHostException error){failures.add(error);log.accept("ePDG DNS 无结果，尝试下个名称");continue;}
            for(InetAddress remote:addresses){
                if(closed)throw new IOException("IKE session closed");
                if(tried>=8||System.nanoTime()>=setupDeadline)break;
                tried++;resetAttempt();
                try{return connectEndpoint(candidate,remote,identity,card,profile);}
                catch(Exception error){
                    if(authenticationStarted||!retryableEndpoint(error))throw error;
                    failures.add(error);log.accept("ePDG 地址不可达或不接受套件，尝试下个地址");
                }finally{if(esp==null&&socket!=null)socket.close();}
            }
        }
        IOException error=new IOException("ePDG 连接失败 · 已尝试 "+tried+" 个地址"+(failures.isEmpty()?"":" · "+failures.get(failures.size()-1).getMessage()));
        for(Exception failure:failures)error.addSuppressed(failure);
        throw error;
    }
    private void resetAttempt(){
        if(socket!=null)socket.close();wipeKeys();spiR=new byte[8];natt=false;authenticationStarted=false;fragmentation=false;
        System.arraycopy(Crypto.random(8),0,spiI,0,8);System.arraycopy(Crypto.random(32),0,ni,0,32);
    }
    private static boolean retryableEndpoint(Exception error){return error instanceof SocketTimeoutException||error instanceof PortUnreachableException||error instanceof NoRouteToHostException||error instanceof NegotiationRejected;}
    private static final class NegotiationRejected extends IOException {NegotiationRejected(int code){super("ePDG IKE negotiation rejected: "+code);}}
    private Tunnel connectEndpoint(String host,InetAddress remote,SimCard.Identity identity,SimCard card,CarrierProfile profile) throws Exception {
        log.accept("ePDG 地址 "+remote.getHostAddress());
        socket=new DatagramSocket();if(closed){socket.close();throw new IOException("IKE session closed");}protector.protect(socket);socket.connect(remote,initialPort);log.accept("IKE_SA_INIT · UDP 500");
        BigInteger secret=null,prime=P;int group=policy.initialGroup(profile),dhLength=256;List<Payload> response=null;
        for(int negotiation=0;negotiation<2;negotiation++){
        prime=group==14?P:P1024;dhLength=group==14?256:128;secret=new BigInteger(256,Crypto.RANDOM).add(BigInteger.TWO);
        byte[] publicValue=Crypto.fixed(BigInteger.TWO.modPow(secret,prime),dhLength),cookie=null;
        for(int attempts=0;attempts<4;attempts++){
            List<Payload> initial=new ArrayList<>();if(cookie!=null)initial.add(p(41,IkeWire.notify(16390,cookie)));
            initial.add(p(33,offer(true,new byte[0],group,group==2)));initial.add(p(34,cat(n16(group),b(0,0),publicValue)));initial.add(p(40,ni));
            // An unknown source forces UDP encapsulation, which works without raw socket privileges.
            initial.add(p(41,IkeWire.notify(16388,Crypto.random(20))));
            initial.add(p(41,IkeWire.notify(16389,Crypto.digest("SHA-1",cat(spiI,new byte[8],remote.getAddress(),n16(natt?nattPort:initialPort))))));
            initial.add(p(41,IkeWire.notify(16430,new byte[0])));
            initI=encode(spiI,new byte[8],34,8,0,initial);
            try{initR=exchange(initI,34,0,false);}catch(SocketTimeoutException timeout){
                if(natt)throw new SocketTimeoutException("ePDG IKE_SA_INIT 无响应（UDP 500 / 4500）");
                socket.disconnect();socket.connect(remote,nattPort);natt=true;attempts--;log.accept("UDP 500 无响应，尝试 NAT-T 4500");continue;
            }
            Message m=decode(initR);spiR=m.responder();response=parse(m.first(),m.body());cookie=null;
            cookie=readCookie(response);
            if(cookie==null)break;
        }
        if(cookie!=null)throw new IOException("ePDG repeated COOKIE challenge");
        int retryGroup=negotiationGroupRequested(response,group);
        if(negotiation==0&&policy==IkePolicy.AUTO&&retryGroup!=0){
            group=retryGroup;System.arraycopy(Crypto.random(8),0,spiI,0,8);System.arraycopy(Crypto.random(32),0,ni,0,32);spiR=new byte[8];
            log.accept(group==2?"ePDG 拒绝 MODP14，尝试兼容套件 MODP2":"ePDG 拒绝 MODP2，尝试 MODP14");continue;
        }
        checkNotify(response);break;
        }
        if(Arrays.equals(spiR,new byte[8]))throw new IOException("IKE responder SPI is zero");
        for(Payload payload:response)if(payload.type()==41&&Arrays.equals(payload.body(),IkeWire.notify(16430,new byte[0])))fragmentation=true;
        log.accept(fragmentation?"IKE 分片已协商":"IKE 对端未启用分片");
        suite=select(get(response,33),true,group).suite();log.accept("IKE 套件 "+suite.description());byte[] ke=get(response,34);nr=get(response,40);
        if(ke==null||ke.length!=4+dhLength||u16(ke,0)!=group||nr==null||nr.length<16||nr.length>256)throw new IOException("Invalid IKE key exchange");
        BigInteger peer=new BigInteger(1,cut(ke,4,dhLength));if(peer.compareTo(BigInteger.TWO)<0||peer.compareTo(prime.subtract(BigInteger.TWO))>0)throw new IOException("Invalid DH public value");
        byte[] dh=Crypto.fixed(peer.modPow(secret,prime),dhLength),seed=suite.prf(cat(ni,nr),dh);
        byte[] keys=suite.expand(seed,cat(ni,nr,spiI,spiR),suite.ikeKeyLength());int off=0,pf=suite.prfLength(),mac=suite.integrityLength(),enc=suite.encryptionLength();skd=cut(keys,off,pf);off+=pf;ai=cut(keys,off,mac);off+=mac;ar=cut(keys,off,mac);off+=mac;ei=cut(keys,off,enc);off+=enc;er=cut(keys,off,enc);off+=enc;pi=cut(keys,off,pf);off+=pf;pr=cut(keys,off,pf);Arrays.fill(dh,(byte)0);Arrays.fill(seed,(byte)0);Arrays.fill(keys,(byte)0);
        socket.disconnect();socket.connect(remote,nattPort);natt=true;nextId=1;authenticationStarted=true;
        String eapIdentity=eapPolicy.identity(identity.nai());
        idI=cat(b(3,0,0,0),eapIdentity.getBytes(StandardCharsets.UTF_8));
        byte[] apn="ims".getBytes(StandardCharsets.US_ASCII);
        List<Payload> request=new ArrayList<>(List.of(p(35,idI),p(41,IkeWire.notify(16417,new byte[0])),
            p(47,cat(b(1,0,0,0),n16(1),n16(0),n16(20),n16(0))),p(33,offer(false,childSpi,0,group==2)),p(44,trafficSelector()),p(45,trafficSelector())));
        // IDr carries the IMS APN as an FQDN (TS 24.302).
        request.add(1,p(36,cat(b(2,0,0,0),apn)));
        boolean peerVerified=false,eapSuccess=false;byte[] msk=null;
        try(EapAka eap=new EapAka(card,eapIdentity,eapPolicy)){
            for(int round=0;round<20;round++){
                List<Payload> ps=protectedExchange(35,request);checkNotify(ps);byte[] rid=get(ps,36);if(rid!=null)idR=rid;
                byte[] auth=get(ps,39);if(auth!=null){if(idR==null)throw new IOException("Missing responder identity");verifyAuth(auth,ps,host,msk);peerVerified=true;}
                byte[] ep=get(ps,48);
                if(ep!=null){byte[] answer=eap.respond(ep);if(answer==null){eapSuccess=true;eapMethod=eap.method();log.accept("EAP-"+eapMethod+" 鉴权通过");msk=eap.msk();byte[] signed=cat(initI,nr,suite.prf(pi,idI));request=List.of(p(39,cat(b(2,0,0,0),suite.prf(suite.prf(msk,PAD),signed))));}else request=List.of(p(48,answer));continue;}
                if(!eapSuccess||!peerVerified)throw new IOException("IKE peer authentication incomplete");
                Selection child=select(get(ps,33),false,0);byte[] remoteSpi=child.spi();String address=null;List<String> pcscf=new ArrayList<>();byte[] cp=get(ps,47);
                if(cp==null||cp.length<4||u(cp[0])!=2)throw new IOException("ePDG omitted configuration reply");
                for(int p=4;p<cp.length;){range(cp,p,4);int type=u16(cp,p)&0x7fff,len=u16(cp,p+2);range(cp,p+4,len);byte[] val=cut(cp,p+4,len);if(type==1&&len==4)address=InetAddress.getByAddress(val).getHostAddress();if(type==20&&len==4)pcscf.add(InetAddress.getByAddress(val).getHostAddress());p+=4+len;}
                if(address==null)throw new IOException("ePDG did not assign IPv4 address");
                validateSelectors(get(ps,44),address,true);validateSelectors(get(ps,45),address,false);
                byte[] childKeys=suite.expand(skd,cat(ni,nr),child.suite().childKeyLength());esp=new Esp(childSpi,remoteSpi,childKeys,child.suite());Arrays.fill(childKeys,(byte)0);
                long now=System.nanoTime();IkeSa sa=new IkeSa(spiI,spiR,suite,true,skd,ai,ar,ei,er,now);sa.nextLocal=nextId;
                control=new IkeControlPlane(sa,new EspChildren(esp,now),fragmentation,policy,address,packets->{for(byte[] packet:packets)sendControl(packet);},log,now,profile.timing());
                wipeKeys();
                log.accept("ePDG 鉴权通过，隧道已建立");return new Tunnel(address,32,List.copyOf(pcscf),host,esp);
            }
            throw new IOException("IKE authentication exceeded round limit");
        }finally{if(msk!=null)Arrays.fill(msk,(byte)0);}
    }
    static boolean legacyNegotiationRequested(List<Payload> response){
        return negotiationGroupRequested(response,14)==2;
    }
    static int negotiationGroupRequested(List<Payload> response,int currentGroup){
        boolean retry=false;
        int alternative=currentGroup==14?2:14;
        for(Payload payload:response)if(payload.type()==41){byte[] value=payload.body();if(value.length<4||value[0]!=0||value[1]!=0)return 0;int code=u16(value,2);if(code>=16384)continue;if(code==14&&value.length==4||code==17&&value.length==6&&u16(value,4)==alternative)retry=true;else return 0;}
        return retry?alternative:0;
    }
    static byte[] readCookie(List<Payload> response)throws IOException{
        byte[] cookie=null;
        for(Payload payload:response)if(payload.type()==41){byte[] value=payload.body();if(value.length<4||u(value[1])>value.length-4)throw new IOException("Invalid IKE notify");if(u16(value,2)==16390){if(cookie!=null||value[0]!=0||value[1]!=0||value.length<5||value.length>68)throw new IOException("Invalid IKE COOKIE");cookie=cut(value,4,value.length-4);}}
        return cookie;
    }
    static void validateSelectors(byte[] ts,String address,boolean local) throws IOException{
        if(ts==null||ts.length<20||u(ts[0])<1)throw new IOException("Missing IKE traffic selectors");
        int count=0;boolean covers=false;
        for(int p=4;p<ts.length;){if(p+16>ts.length)throw new IOException("Truncated traffic selector");int n=u16(ts,p+2);if(u(ts[p])!=7||n!=16||u(ts[p+1])!=0||u16(ts,p+4)!=0||u16(ts,p+6)!=65535)throw new IOException("Narrowed traffic selectors require a route policy");long start=u32(ts,p+8),end=u32(ts,p+12);if(start>end)throw new IOException("Invalid selector address range");if(local){long ip=u32(InetAddress.getByName(address).getAddress(),0);covers|=ip>=start&&ip<=end;}else covers|=start==0&&end==0xffffffffL;p+=n;count++;}
        if(count!=u(ts[0])||!covers)throw new IOException("Traffic selectors do not cover the IMS route");
    }
    private void verifyAuth(byte[] auth,List<Payload> ps,String host,byte[] msk) throws Exception {
        if(auth.length<4)throw new IOException("Invalid responder AUTH");byte[] signed=cat(initR,ni,suite.prf(pr,idR));int method=u(auth[0]);
        if(method==2){if(msk==null)throw new IOException("Responder shared-key AUTH before EAP success");if(!MessageDigest.isEqual(cut(auth,4,auth.length-4),suite.prf(suite.prf(msk,PAD),signed)))throw new IOException("Invalid responder AUTH");return;}
        List<X509Certificate> certs=new ArrayList<>();CertificateFactory f=CertificateFactory.getInstance("X.509");for(Payload p:ps)if(p.type()==37&&p.body().length>1&&u(p.body()[0])==4)certs.add((X509Certificate)f.generateCertificate(new ByteArrayInputStream(cut(p.body(),1,p.body().length-1))));
        if(certs.isEmpty())throw new IOException("Responder certificate missing");
        TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init((KeyStore)null);boolean trusted=false;for(TrustManager tm:tmf.getTrustManagers())if(tm instanceof X509TrustManager x){x.checkServerTrusted(certs.toArray(new X509Certificate[0]),certs.get(0).getPublicKey().getAlgorithm());trusted=true;}
        if(!trusted||!certificateMatches(certs.get(0),host))throw new IOException("ePDG certificate hostname mismatch");
        String algorithm;int offset=4;
        if(method==1)algorithm="SHA1withRSA";else if(method==14){if(auth.length<6)throw new IOException("Invalid digital signature AUTH");int len=u(auth[4]);range(auth,5,len);String oid=hex(cut(auth,5,len));if(oid.equals("300D06092A864886F70D01010B0500"))algorithm="SHA256withRSA";else if(oid.equals("300A06082A8648CE3D040302"))algorithm="SHA256withECDSA";else throw new IOException("Unsupported responder signature algorithm");offset=5+len;}else throw new IOException("Unsupported responder AUTH method");
        Signature verifier=Signature.getInstance(algorithm);verifier.initVerify(certs.get(0));verifier.update(signed);if(!verifier.verify(cut(auth,offset,auth.length-offset)))throw new IOException("Responder certificate signature invalid");
    }
    static boolean certificateMatches(X509Certificate c,String host) throws Exception {Collection<List<?>> sans=c.getSubjectAlternativeNames();if(sans==null)return false;host=host.toLowerCase(Locale.ROOT);for(List<?> san:sans)if(san.get(0).equals(2)){String name=san.get(1).toString().toLowerCase(Locale.ROOT);if(name.equals(host))return true;if(name.startsWith("*.")){String suffix=name.substring(1);if(host.endsWith(suffix)&&host.substring(0,host.length()-suffix.length()).indexOf('.')<0)return true;}}return false;}
    private byte[] exchange(byte[] request,int exchange,long id,boolean protectedMessage) throws Exception {
        for(int retry=0;retry<(protectedMessage?4:3);retry++){
            if(closed)throw new IOException("IKE session closed");byte[] wire=natt?cat(new byte[4],request):request;socket.send(new DatagramPacket(wire,wire.length));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(8000,(long)retryBaseMillis<<retry));
            if(!protectedMessage)deadline=Math.min(deadline,setupDeadline);
            while(System.nanoTime()<deadline){socket.setSoTimeout((int)Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));byte[] buf=new byte[65535];DatagramPacket packet=new DatagramPacket(buf,buf.length);try{socket.receive(packet);}catch(SocketTimeoutException e){break;}byte[] raw=cut(buf,0,packet.getLength());if(natt){if(raw.length<4||u32(raw,0)!=0)continue;raw=cut(raw,4,raw.length-4);}Message m;try{m=decode(raw);}catch(IOException e){continue;}if(!Arrays.equals(m.initiator(),spiI)||m.id()!=id||m.exchange()!=exchange||(m.flags()&0x28)!=0x20)continue;if(protectedMessage&&!Arrays.equals(m.responder(),spiR))continue;return raw;}
        }
        throw new SocketTimeoutException("ePDG IKE response timed out");
    }
    private List<Payload> protectedExchange(int exchange,List<Payload> payloads) throws Exception {
        long id=nextId++;List<byte[]> requests=IkeEncrypted.seal(new Message(spiI,spiR,exchange,8,id,0,new byte[0]),payloads,suite,ei,ai,fragmentation);
        try(IkeEncrypted.Reassembly assembly=new IkeEncrypted.Reassembly()){
            for(int retry=0;retry<4;retry++){
                if(closed)throw new IOException("IKE session closed");
                for(byte[] request:requests)sendControl(request);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(8000,(long)retryBaseMillis<<retry));
                while(System.nanoTime()<deadline){
                    socket.setSoTimeout((int)Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
                    byte[] buffer=new byte[65535];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);try{socket.receive(packet);}catch(SocketTimeoutException timeout){break;}
                    if(packet.getLength()<32||u32(buffer,0)!=0)continue;byte[] wire=cut(buffer,4,packet.getLength()-4);Message message;
                    try{message=decode(wire);}catch(IOException invalid){continue;}
                    if(!Arrays.equals(message.initiator(),spiI)||!Arrays.equals(message.responder(),spiR)||message.id()!=id||message.exchange()!=exchange||(message.flags()&0x28)!=0x20)continue;
                    IkeEncrypted.Decoded decoded;
                    try{decoded=assembly.accept(wire,suite,er,ar,fragmentation,System.nanoTime());}catch(IOException invalid){continue;}
                    if(decoded!=null)return decoded.payloads();
                }
            }
        }
        throw new SocketTimeoutException("ePDG encrypted IKE response timed out");
    }
    private static void checkNotify(List<Payload> ps) throws IOException{int rejection=0;for(Payload p:ps)if(p.type()==41){if(p.body().length<4||u(p.body()[1])>p.body().length-4)throw new IOException("Invalid IKE notify");int n=u16(p.body(),2);if(n==14||n==17)rejection=n;else if(n<16384)throw new IOException("ePDG IKE notify "+n);}if(rejection!=0)throw new NegotiationRejected(rejection);}
    public void sendInner(byte[] ip) throws Exception {
        IkeControlPlane plane=control;if(closed||plane==null)throw new IOException("Tunnel closed");byte[] enc=plane.children.encrypt(ip);socket.send(new DatagramPacket(enc,enc.length));
    }
    public interface PacketReceiver {void receive(byte[] packet) throws Exception;}
    public void runDataplane(PacketReceiver receiver) throws Exception {
        IkeControlPlane plane=control;if(plane==null)throw new IOException("Tunnel not established");socket.setSoTimeout(1000);
        long keepalive=System.nanoTime();
        try{while(!closed){byte[] b=new byte[65535];DatagramPacket d=new DatagramPacket(b,b.length);try{
            socket.receive(d);byte[] a=cut(b,0,d.getLength());
            if(a.length>=4&&u32(a,0)!=0){byte[] inner=null;try{inner=plane.children.decrypt(a,System.nanoTime());}catch(IOException ignored){}if(inner!=null){plane.authenticated(System.nanoTime());receiver.receive(inner);}}
            else if(a.length>=32)plane.receive(cut(a,4,a.length-4),System.nanoTime());
        }catch(SocketTimeoutException ignored){}
            long now=System.nanoTime();
            plane.tick(now);
            if(now-keepalive>java.util.concurrent.TimeUnit.SECONDS.toNanos(20)){socket.send(new DatagramPacket(Bytes.b(255),1));keepalive=now;}
        }}finally{close();}
    }
    private void sendControl(byte[] request)throws IOException{byte[] wire=cat(new byte[4],request);socket.send(new DatagramPacket(wire,wire.length));}
    private void wipeKeys(){for(byte[] k:new byte[][]{skd,ai,ar,ei,er,pi,pr})if(k!=null)Arrays.fill(k,(byte)0);}
    public void close(){closed=true;if(socket!=null)socket.close();IkeControlPlane plane=control;if(plane!=null)plane.close();else if(esp!=null)esp.close();wipeKeys();}
}
