package io.vodroid.core;

import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static io.vodroid.core.Bytes.*;

/** SIP registration, SMS over IMS, and one MMTEL audio dialog. */
public final class ImsSession implements AutoCloseable {
    public record Config(String pcscf,int port,boolean tcp,String smsc,boolean ipsecRequired,String imei){}
    public record Call(String id,String number,String state,boolean incoming,String codec){}
    public interface Listener {
        void state(String state);
        void sms(String from,String text);
        default void sms(String id,String from,String text){sms(from,text);}
        default void event(String event){state(event);}
        void smsStatus(String id,String status);
        void call(Call call);
        void audio(byte[] encoded,RtpEndpoint.Format format);
    }
    private final Config config;
    private final PacketNetwork network;
    private final SimCard card;
    private final SimCard.Identity identity;
    private final Listener listener;
    private final PacketNetwork.Port client,server;
    private volatile TcpStream tcp;
    private PacketNetwork.TcpListener tcpListener;
    private final ImsIpsec security;
    private String verify="",authorization="",contact,publicIdentity;
    private final String privateIdentity,domain,tag=token(),registrationId=token();
    private final AtomicLong sequence=new AtomicLong(1);
    private final ConcurrentMap<String,BlockingQueue<SipMessage>> pending=new ConcurrentHashMap<>();
    private static final class Submission {final String id;final java.util.concurrent.atomic.AtomicInteger remaining;final long created=System.currentTimeMillis();Submission(String id,int count){this.id=id;remaining=new java.util.concurrent.atomic.AtomicInteger(count);}}
    private final Map<Integer,Submission> smsReferences=new ConcurrentHashMap<>();
    private final SmsCodec.Assembler assembler=new SmsCodec.Assembler();
    private final ScheduledExecutorService worker=Executors.newScheduledThreadPool(6);
    private final List<String> routes=new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String,byte[]> requestResponses=new LinkedHashMap<>();
    private final Object incomingLock=new Object();
    private final LinkedHashMap<String,SmsReceipt> receipts=new LinkedHashMap<>();
    private final Set<String> associatedIdentities=ConcurrentHashMap.newKeySet();
    private static final class SmsReceipt {final byte[] packet;final String kind;boolean sending,complete;int attempts;long last;SmsReceipt(byte[] packet,String kind){this.packet=packet;this.kind=kind;}}
    private interface ReplySender {void send(byte[] raw) throws Exception;}
    private final ConcurrentMap<String,ReplySender> replySenders=new ConcurrentHashMap<>();
    private volatile boolean registered,closed;
    private ScheduledFuture<?> renewal;
    private int requestedExpiry=600,renewalFailures;
    private long registrationDeadline;
    private static final class RegistrationRejected extends IOException {
        final int code,retryAfter;
        RegistrationRejected(SipMessage message){super("IMS REGISTER rejected: "+message.code());code=message.code();int delay=5;try{delay=Integer.parseInt(message.header("retry-after").split("[; ]",2)[0]);}catch(NumberFormatException ignored){}retryAfter=Math.max(1,Math.min(3600,delay));}
        boolean temporary(){return code==408||code==429||code==500||code==502||code==503||code==504;}
    }
    private volatile Dialog dialog;
    private int peerPort;
    private static class Dialog {String id,number,from,to,target,branch;long cseq;boolean incoming,provisional;String state;RtpEndpoint rtp;SipMessage invite;List<String> routes=new ArrayList<>();byte[] lastInvite;final Set<String> rseqs=ConcurrentHashMap.newKeySet();}
    public ImsSession(Config config,PacketNetwork network,SimCard card,SimCard.Identity identity,Listener listener) throws Exception{
        this.config=config;this.network=network;this.card=card;this.identity=identity;this.listener=listener;domain=identity.imsDomain();privateIdentity=identity.imsi()+"@"+domain;publicIdentity="sip:"+privateIdentity;
        client=network.openUdp(0);server=network.openUdp(0);peerPort=config.port();security=new ImsIpsec(client.number,server.number);network.security(security);
        contact="<sip:"+identity.imsi()+"@"+network.address()+":"+server.number+";transport="+(config.tcp()?"tcp":"udp")+">;+g.3gpp.smsip;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
        try{if(config.tcp()){tcpListener=network.listenTcp(server.number);worker.execute(this::acceptTcp);tcp=network.connectTcp(config.pcscf(),peerPort,client.number);TcpStream stream=tcp;worker.execute(()->readTcp(stream));}else{worker.execute(()->readUdp(client));worker.execute(()->readUdp(server));}}catch(Exception error){close();throw error;}
    }
    private static String token(){return hex(Crypto.random(12)).toLowerCase(Locale.ROOT);}
    private String transport(){return config.tcp()?"TCP":"UDP";}
    private String via(String branch) throws Exception{return "Via: SIP/2.0/"+transport()+" "+network.address()+":"+client.number+";branch=z9hG4bK"+branch+";rport";}
    private List<String> base(String method,String id,long seq,String from,String to,String branch) throws Exception{
        List<String> h=new ArrayList<>(List.of(via(branch),"Max-Forwards: 70","From: "+from,"To: "+to,"Call-ID: "+id,"CSeq: "+seq+" "+method,"User-Agent: VoDroid/0.1","P-Access-Network-Info: IEEE-802.11"));if(!verify.isEmpty())h.add("Security-Verify: "+verify);return h;
    }
    private void send(byte[] raw) throws Exception{if(closed)throw new IOException("IMS session closed");if(config.tcp())tcp.write(raw);else client.send(config.pcscf(),peerPort,raw);}
    private static String key(SipMessage m){return m.header("call-id")+"/"+m.header("cseq");}
    private SipMessage transact(byte[] raw,long timeout) throws Exception{
        SipMessage request=SipMessage.parse(raw);String key=key(request);BlockingQueue<SipMessage> queue=new ArrayBlockingQueue<>(16);if(pending.putIfAbsent(key,queue)!=null)throw new IOException("SIP transaction collision");long nextSend=System.nanoTime(),deadline=nextSend+TimeUnit.MILLISECONDS.toNanos(timeout),interval=500;
        try{while(!closed){long now=System.nanoTime();if(now>=deadline)throw new SocketTimeoutException("SIP "+request.method()+" timed out");if(now>=nextSend){send(raw);nextSend=config.tcp()?deadline:now+TimeUnit.MILLISECONDS.toNanos(interval);interval=Math.min(4000,interval*2);}SipMessage response=queue.poll(Math.max(1,TimeUnit.NANOSECONDS.toMillis(Math.min(deadline,nextSend)-now)),TimeUnit.MILLISECONDS);if(response!=null&&response.code()>=200)return response;}throw new IOException("IMS disconnected");}finally{pending.remove(key);}
    }
    public synchronized void register() throws Exception{
        if(closed)throw new IOException("IMS session closed");
        if(renewal!=null){renewal.cancel(false);renewal=null;}
        // A digest response with nc=1 belongs to its original transaction. Ask for
        // a fresh challenge instead of replaying that response on a later REGISTER.
        authorization="";
        listener.state("正在注册 IMS");String uri="sip:"+domain;
        for(int round=0;round<4;round++){
            long seq=sequence.getAndIncrement();List<String> h=base("REGISTER",registrationId,seq,"<"+publicIdentity+">;tag="+tag,"<"+publicIdentity+">",token());h.addAll(List.of("Contact: "+contact,"Expires: "+requestedExpiry,"Supported: path, gruu, sec-agree","Allow: INVITE, ACK, CANCEL, BYE, MESSAGE, OPTIONS, UPDATE, PRACK","Security-Client: "+security.offer()));
            h.add("Authorization: "+(authorization.isEmpty()?"Digest username=\""+privateIdentity+"\", realm=\""+domain+"\", nonce=\"\", uri=\""+uri+"\", response=\"\"":authorization));
            SipMessage response=transact(SipMessage.encode("REGISTER "+uri+" SIP/2.0",h,new byte[0]),15000);
            if(response.code()==401){try(SipDigest.Result digest=SipDigest.answer(response.header("www-authenticate"),privateIdentity,"REGISTER",uri,new byte[0],card)){authorization=digest.authorization();if(!digest.auts().isEmpty())continue;if(!response.all("security-server").isEmpty()){verify=security.install(response.all("security-server"),digest.ik(),config.pcscf());peerPort=security.remoteServer();if(config.tcp()){TcpStream old=tcp;tcp=null;old.close();tcp=network.connectTcp(config.pcscf(),peerPort,client.number);TcpStream stream=tcp;worker.execute(()->readTcp(stream));}}else if(config.ipsecRequired())throw new IOException("P-CSCF omitted required IMS IPsec agreement");}continue;}
            if(response.code()==423){int minimum;try{minimum=Integer.parseInt(response.header("min-expires"));}catch(NumberFormatException error){throw new IOException("IMS 423 omitted valid Min-Expires");}if(minimum<=requestedExpiry||minimum>86400)throw new IOException("IMS Min-Expires outside supported range");requestedExpiry=minimum;continue;}
            if(response.code()!=200)throw new RegistrationRejected(response);if(config.ipsecRequired()&&!security.active())throw new IOException("IMS registered without required IPsec protection");
            routes.clear();routes.addAll(response.all("service-route"));associatedIdentities.clear();for(String a:response.all("p-associated-uri"))for(String value:a.split(",")){String associated=SipMessage.uri(value);if(associated.startsWith("sip:")||associated.startsWith("tel:"))associatedIdentities.add(associated);}for(String a:response.all("p-associated-uri")){String associated=SipMessage.uri(a.split(",",2)[0]);if(associatedIdentities.contains(associated)){publicIdentity=associated;break;}}
            int expires=registrationExpiry(response,contact,requestedExpiry);
            if(expires<=0)throw new IOException("IMS returned an expired registration");
            registrationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(expires);renewalFailures=0;
            registered=true;listener.state("IMS 已注册");scheduleRenewal(Math.max(1,expires*4/5));return;
        }throw new IOException("IMS AKA challenge limit reached");
    }
    static int registrationExpiry(SipMessage response,String contact,int fallback)throws IOException{
        String expiry=response.header("expires");
        for(String header:response.all("contact"))for(String binding:header.split(","))if(SipMessage.uri(binding).equals(SipMessage.uri(contact))){
            String parameters=binding.substring(Math.max(0,binding.indexOf('>')+1));
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?:^|;)\\s*expires\\s*=\\s*([0-9]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(parameters);
            if(matcher.find())expiry=matcher.group(1);
        }
        if(expiry.isEmpty())return fallback;
        try{return Math.min(86400,Math.max(0,Integer.parseInt(expiry)));}catch(NumberFormatException error){throw new IOException("Invalid IMS registration expiry");}
    }
    private synchronized void scheduleRenewal(int delay){
        if(closed)return;
        renewal=worker.schedule(()->{try{register();}catch(Exception error){synchronized(this){
            if(closed)return;
            long remaining=TimeUnit.NANOSECONDS.toSeconds(registrationDeadline-System.nanoTime());
            boolean temporary=error instanceof SocketTimeoutException||error instanceof RegistrationRejected rejected&&rejected.temporary();
            if(!temporary||remaining<=1){registered=false;listener.state("IMS 续期失败: "+error.getMessage());return;}
            int retry=error instanceof RegistrationRejected rejected?rejected.retryAfter:Math.min(60,5<<Math.min(renewalFailures++,4));
            if(retry>=remaining){registered=false;listener.state("IMS 续期等待超过注册有效期");return;}
            listener.state("IMS 续期重试 · "+retry+"s");scheduleRenewal(retry);
        }}},delay,TimeUnit.SECONDS);
    }
    public boolean registered(){return registered&&(registrationDeadline==0||System.nanoTime()<registrationDeadline);}
    public static boolean retryableRegistrationFailure(Exception error){return error instanceof SocketTimeoutException||error instanceof java.net.ConnectException||error instanceof RegistrationRejected rejected&&rejected.temporary();}
    private void addRoutes(List<String> h,List<String> rs){for(String r:rs)h.add("Route: "+r);}
    private byte[] message(String uri,byte[] body) throws Exception{List<String> h=base("MESSAGE",token(),sequence.getAndIncrement(),"<"+publicIdentity+">;tag="+tag,"<"+uri+">",token());h.addAll(List.of("Content-Type: application/vnd.3gpp.sms","Accept-Contact: *;+g.3gpp.smsip;require;explicit","P-Preferred-Identity: <"+publicIdentity+">"));addRoutes(h,routes);return SipMessage.encode("MESSAGE "+uri+" SIP/2.0",h,body);}
    private static String requestKey(SipMessage m){String via=m.header("via");java.util.regex.Matcher branch=java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*branch=([^;\\s,]+)").matcher(via);return key(m)+"/"+(branch.find()?via.split(";",2)[0].trim().toLowerCase(Locale.ROOT)+"/"+branch.group(1):via);}
    private byte[] deliveryReport(SipMessage incoming,byte[] body)throws Exception{
        String target="";for(String header:List.of("p-asserted-identity","from")){for(String value:incoming.all(header)){String uri=SipMessage.uri(value.split(",",2)[0]);if(uri.matches("(?i)(sips?:[^\\s<>]+|tel:\\+?[0-9][0-9a-z;=+.-]*)")){target=uri;break;}}if(!target.isEmpty())break;}
        if(target.isEmpty())throw new IOException("SMS delivery report has no IP-SM-GW target");
        String preferred=SipMessage.uri(incoming.header("p-called-party-id"));if(!associatedIdentities.contains(preferred))preferred=publicIdentity;
        List<String> headers=base("MESSAGE",token(),sequence.getAndIncrement(),"<"+preferred+">;tag="+tag,"<"+target+">",token());
        headers.addAll(List.of("Content-Type: application/vnd.3gpp.sms","Content-Transfer-Encoding: binary","Accept-Contact: *;+g.3gpp.smsip","Request-Disposition: no-fork","In-Reply-To: "+incoming.header("call-id"),"P-Preferred-Identity: <"+preferred+">"));addRoutes(headers,routes);
        return SipMessage.encode("MESSAGE "+target+" SIP/2.0",headers,body);
    }
    private void reportSms(SipMessage request,byte[] body,String kind)throws Exception{
        String key=requestKey(request);SmsReceipt receipt=new SmsReceipt(deliveryReport(request,body),kind);receipts.put(key,receipt);while(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());retryReport(receipt);
    }
    private void retryReport(SmsReceipt receipt){
        if(receipt==null||closed)return;synchronized(receipt){if(receipt.sending||receipt.complete||receipt.attempts>=3||receipt.attempts>0&&System.nanoTime()-receipt.last<TimeUnit.SECONDS.toNanos(2))return;receipt.sending=true;receipt.attempts++;receipt.last=System.nanoTime();}
        worker.execute(()->{try{SipMessage response=transact(receipt.packet,16000);if(response.code()<200||response.code()>=300)throw new IOException("SIP "+response.code());synchronized(receipt){receipt.complete=true;}listener.event("短信 "+receipt.kind+" 已确认 · SIP "+response.code());}catch(Exception error){listener.event("短信 "+receipt.kind+" 失败 · "+(error instanceof SocketTimeoutException?"超时":error instanceof IOException?error.getMessage():error.getClass().getSimpleName()));}finally{synchronized(receipt){receipt.sending=false;}}});
    }
    private void receiveSms(SipMessage m)throws Exception{
        if(!m.header("content-type").split(";",2)[0].trim().equalsIgnoreCase("application/vnd.3gpp.sms")){respond(m,415,"Unsupported Media Type",new byte[0],List.of());return;}
        if(m.body.length<2){respond(m,400,"Bad Request",new byte[0],List.of());return;}int type=m.body[0]&7,reference=u(m.body[1]);
        respond(m,200,"OK",new byte[0],List.of());
        if(type==1){
            SmsCodec.Incoming incoming;try{incoming=SmsCodec.deliver(m.body);}catch(IOException|IllegalArgumentException invalid){reportSms(m,SmsCodec.error(reference,95),"RP-ERROR");listener.event("短信 TPDU 无法处理 · 已报告网络");return;}
            String id="ims:"+hex(Crypto.digest("SHA-256",(identity.iccid()+"|"+m.header("call-id")+"|"+reference).getBytes(StandardCharsets.UTF_8)));
            try{SmsCodec.Assembled message=assembler.accept(incoming,id);if(message!=null)listener.sms(message.id(),message.sender(),message.text());}
            catch(Exception failed){reportSms(m,SmsCodec.error(reference,22),"RP-ERROR");listener.event("短信本地保存失败 · 已报告网络");return;}
            reportSms(m,SmsCodec.ack(reference),"RP-ACK");
        }else if(type==3||type==5){Submission submission=smsReferences.remove(reference);if(submission!=null){if(type==5){smsReferences.entrySet().removeIf(v->v.getValue()==submission);listener.smsStatus(submission.id,"网络拒绝");}else if(submission.remaining.decrementAndGet()==0)listener.smsStatus(submission.id,"网络已确认（非送达回执）");}}
    }
    private String smsUri(){return "sip:"+config.smsc()+"@"+domain+";user=phone";}
    public void sendSms(String id,String to,String text) throws Exception{
        if(!registered())throw new IOException("IMS 尚未注册");List<SmsCodec.Part> parts=SmsCodec.submit(to,text,config.smsc(),Crypto.RANDOM.nextInt(256));Submission submission=new Submission(id,parts.size());
        synchronized(smsReferences){smsReferences.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().created>120000);for(SmsCodec.Part part:parts)if(smsReferences.containsKey(part.reference()))throw new IOException("RP reference busy; retry later");for(SmsCodec.Part part:parts)smsReferences.put(part.reference(),submission);}
        listener.smsStatus(id,"发送中");
        try{for(SmsCodec.Part part:parts){SipMessage response=transact(message(smsUri(),part.rp()),15000);if(response.code()!=200&&response.code()!=202)throw new IOException("IMS SMS rejected: "+response.code());}if(submission.remaining.get()>0)listener.smsStatus(id,"IMS 已接受（待网络确认）");}catch(Exception e){smsReferences.entrySet().removeIf(v->v.getValue()==submission);listener.smsStatus(id,"失败: "+e.getMessage());throw e;}
    }
    private void readUdp(PacketNetwork.Port port){while(!closed)try{PacketNetwork.Datagram d=port.receive(1000);if(d.host().equals(config.pcscf()))receive(SipMessage.parse(d.data()),raw->port.send(d.host(),d.port(),raw));}catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)listener.state("SIP 接收异常: "+e.getClass().getSimpleName());}}
    private void readTcp(TcpStream stream){try{while(!closed&&stream==tcp){SipMessage m=readTcpMessage(stream.input);if(m==null)throw new EOFException("P-CSCF closed TCP");receive(m,stream::write);}}catch(Exception e){if(!closed&&stream==tcp){registered=false;listener.state("IMS TCP 中断: "+e.getClass().getSimpleName());}}}
    private void acceptTcp(){while(!closed)try{TcpStream stream=tcpListener.accept(1000);worker.execute(()->{try(stream){while(!closed){SipMessage message=readTcpMessage(stream.input);if(message==null)break;receive(message,stream::write);}}catch(Exception e){if(!closed)listener.state("IMS 入站 TCP 中断");}});}catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)listener.state("IMS TCP 监听中断");break;}}
    public static SipMessage readTcpMessage(InputStream input) throws Exception{
        ByteArrayOutputStream header=new ByteArrayOutputStream();int state=0;
        while(header.size()<65536){int b=input.read();if(b<0){if(header.size()==0)return null;throw new EOFException("Truncated SIP TCP header");}header.write(b);
            // CRLF ping/pong is framing, including a single CRLF before a SIP message.
            if(header.size()==2&&state==1&&b==10){header.reset();state=0;continue;}
            state=switch(state){case 0->b==13?1:0;case 1->b==10?2:0;case 2->b==13?3:0;default->b==10?4:0;};if(state==4)break;
        }
        if(state!=4)throw new IOException("SIP TCP header exceeds limit");
        byte[] h=header.toByteArray();String text=new String(h,StandardCharsets.UTF_8);int length=-1;
        for(String line:text.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")||line.toLowerCase(Locale.ROOT).startsWith("l:")){
            if(length!=-1)throw new IOException("Duplicate SIP content length");
            try{length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}catch(NumberFormatException error){throw new IOException("Invalid SIP content length");}
        }
        if(length<0||length>1024*1024)throw new IOException("Missing or invalid SIP TCP content length");byte[] body=input.readNBytes(length);if(body.length!=length)throw new IOException("Truncated SIP TCP body");return SipMessage.parse(cat(h,body));
    }
    private void receive(SipMessage m,ReplySender responder) throws Exception{
        if(m.code()>0){BlockingQueue<SipMessage> q=pending.get(key(m));if(q!=null)q.offer(m);Dialog d=dialog;if(d!=null&&m.header("call-id").equals(d.id)&&m.header("cseq").endsWith("INVITE"))handleInviteResponse(d,m);return;}
        synchronized(incomingLock){receiveRequest(m,responder);}
    }
    private void receiveRequest(SipMessage m,ReplySender responder)throws Exception{
        String requestKey=requestKey(m);if(!m.method().equals("ACK"))replySenders.put(requestKey,responder);byte[] cached;synchronized(requestResponses){cached=requestResponses.get(requestKey);}if(cached!=null){responder.send(cached);retryReport(receipts.get(requestKey));return;}
        switch(m.method()){
            case "OPTIONS"->{respond(m,200,"OK",new byte[0],List.of("Allow: INVITE, ACK, CANCEL, BYE, MESSAGE, OPTIONS, UPDATE, PRACK"));}
            case "MESSAGE"->receiveSms(m);
            case "INVITE"->{if(dialog!=null){respond(m,486,"Busy Here",new byte[0],List.of());break;}Dialog d=new Dialog();d.id=m.header("call-id");d.number=SipMessage.uri(m.header("from"));d.from=m.header("from");d.to=m.header("to")+";tag="+token();d.target=SipMessage.uri(m.header("contact"));d.cseq=Long.parseLong(m.header("cseq").split(" ")[0]);d.incoming=true;d.invite=m;d.routes.addAll(m.all("record-route"));d.rtp=new RtpEndpoint(network,data->{RtpEndpoint.Format f=dialog==null?null:dialog.rtp.format();if(f!=null)listener.audio(data,f);});try{d.rtp.negotiate(m.text());}catch(Exception e){d.rtp.close();respond(m,488,"Not Acceptable Here",new byte[0],List.of());break;}dialog=d;d.state="ringing";respond(m,180,"Ringing",new byte[0],List.of());emitCall(d);}
            case "ACK"->{Dialog d=dialog;if(d!=null&&d.incoming&&m.header("call-id").equals(d.id)){d.state="active";emitCall(d);}}
            case "BYE"->{Dialog d=dialog;if(d!=null&&m.header("call-id").equals(d.id)){respond(m,200,"OK",new byte[0],List.of());finishCall(d);}else respond(m,481,"Call Does Not Exist",new byte[0],List.of());}
            case "CANCEL"->{Dialog d=dialog;if(d!=null&&d.incoming&&d.state.equals("ringing")&&m.header("call-id").equals(d.id)){respond(m,200,"OK",new byte[0],List.of());respond(d.invite,487,"Request Terminated",new byte[0],List.of());finishCall(d);}else respond(m,481,"Call Does Not Exist",new byte[0],List.of());}
            case "PRACK"->{respond(m,200,"OK",new byte[0],List.of());}
            case "UPDATE"->{Dialog d=dialog;if(d==null||!m.header("call-id").equals(d.id))respond(m,481,"Call Does Not Exist",new byte[0],List.of());else{if(m.body.length>0)d.rtp.negotiate(m.text());respond(m,200,"OK",m.body.length==0?new byte[0]:d.rtp.answerSdp().getBytes(StandardCharsets.UTF_8),m.body.length==0?List.of():List.of("Content-Type: application/sdp"));}}
            default->respond(m,405,"Method Not Allowed",new byte[0],List.of());
        }
    }
    private void respond(SipMessage request,int code,String reason,byte[] body,List<String> extras) throws Exception{
        List<String> h=new ArrayList<>();for(String v:request.all("via"))h.add("Via: "+v);String to=request.header("to");Dialog d=dialog;if(d!=null&&d.id.equals(request.header("call-id"))&&d.incoming)to=d.to;else if(SipMessage.tag(to).isEmpty())to+=";tag="+tag;h.addAll(List.of("From: "+request.header("from"),"To: "+to,"Call-ID: "+request.header("call-id"),"CSeq: "+request.header("cseq")));h.addAll(extras);byte[] raw=SipMessage.encode("SIP/2.0 "+code+" "+reason,h,body);String requestKey=requestKey(request);if(code>=100){synchronized(requestResponses){requestResponses.put(requestKey,raw);while(requestResponses.size()>256){String oldest=requestResponses.keySet().iterator().next();requestResponses.remove(oldest);replySenders.remove(oldest);}}}ReplySender sender=replySenders.get(requestKey);if(sender!=null)sender.send(raw);else send(raw);
    }
    public synchronized Call dial(String number) throws Exception{
        if(!registered())throw new IOException("IMS 尚未注册");if(dialog!=null)throw new IOException("已有通话");if(!number.matches("\\+?[0-9*#]{1,30}"))throw new IOException("号码无效");Dialog d=new Dialog();d.id=token();d.number=number;d.target="sip:"+number+"@"+domain+";user=phone";d.from="<"+publicIdentity+">;tag="+tag;d.to="<"+d.target+">";d.cseq=sequence.getAndIncrement();d.branch=token();d.state="dialing";d.routes.addAll(routes);d.rtp=new RtpEndpoint(network,data->{RtpEndpoint.Format f=dialog==null?null:dialog.rtp.format();if(f!=null)listener.audio(data,f);});List<String> h=base("INVITE",d.id,d.cseq,d.from,d.to,d.branch);h.addAll(List.of("Contact: "+contact,"Content-Type: application/sdp","Supported: 100rel, timer","Session-Expires: 1800","P-Preferred-Identity: <"+publicIdentity+">","Accept-Contact: *;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\""));addRoutes(h,d.routes);d.lastInvite=SipMessage.encode("INVITE "+d.target+" SIP/2.0",h,d.rtp.sdp().getBytes(StandardCharsets.UTF_8));dialog=d;send(d.lastInvite);retryInvite(d,500);emitCall(d);worker.schedule(()->{if(dialog==d&&!d.state.equals("active"))try{hangup();}catch(Exception ignored){}},45,TimeUnit.SECONDS);return snapshot(d);
    }
    private void handleInviteResponse(Dialog d,SipMessage m) throws Exception{
        int code=m.code();if(code<200)d.provisional=true;if(code<180)return;if(!m.header("to").isEmpty())d.to=m.header("to");
        if(code<200){if(m.body.length>0)d.rtp.negotiate(m.text());d.state="ringing";emitCall(d);if(!m.header("rseq").isEmpty()&&d.rseqs.add(m.header("rseq"))){List<String> h=base("PRACK",d.id,sequence.getAndIncrement(),d.from,d.to,token());h.add("RAck: "+m.header("rseq")+" "+d.cseq+" INVITE");addRoutes(h,d.routes);worker.execute(()->{try{transact(SipMessage.encode("PRACK "+d.target+" SIP/2.0",h,new byte[0]),12000);}catch(Exception ignored){}});}return;}
        String target=m.header("contact").isEmpty()?d.target:SipMessage.uri(m.header("contact"));if(code<300){if(m.body.length>0)d.rtp.negotiate(m.text());d.target=target;d.routes=new ArrayList<>(m.all("record-route"));Collections.reverse(d.routes);}List<String> h=base("ACK",d.id,d.cseq,d.from,d.to,code<300?token():d.branch);addRoutes(h,d.routes);send(SipMessage.encode("ACK "+d.target+" SIP/2.0",h,new byte[0]));if(code>=300){d.state="failed "+code;emitCall(d);finishCall(d);}else{d.state="active";emitCall(d);}
    }
    private void retryInvite(Dialog d,long delay){if(config.tcp())return;worker.schedule(()->{if(!closed&&dialog==d&&!d.provisional&&d.state.equals("dialing"))try{send(d.lastInvite);retryInvite(d,Math.min(4000,delay*2));}catch(Exception e){finishCall(d);}},delay,TimeUnit.MILLISECONDS);}
    public synchronized void answer() throws Exception{Dialog d=dialog;if(d==null||!d.incoming||!d.state.equals("ringing"))throw new IOException("无待接来电");respond(d.invite,200,"OK",d.rtp.answerSdp().getBytes(StandardCharsets.UTF_8),List.of("Content-Type: application/sdp","Contact: "+contact));d.state="connecting";emitCall(d);}
    public synchronized void hangup() throws Exception{Dialog d=dialog;if(d==null)return;try{if(d.incoming&&d.state.equals("ringing"))respond(d.invite,486,"Busy Here",new byte[0],List.of());else{String method=d.state.equals("active")?"BYE":"CANCEL";List<String> h=base(method,d.id,method.equals("CANCEL")?d.cseq:sequence.getAndIncrement(),d.from,d.to,method.equals("CANCEL")?d.branch:token());if(d.incoming){h=base(method,d.id,sequence.getAndIncrement(),d.to,d.from,token());}addRoutes(h,d.routes);send(SipMessage.encode(method+" "+d.target+" SIP/2.0",h,new byte[0]));}}finally{finishCall(d);}}
    public void sendAudio(byte[] frame) throws Exception{Dialog d=dialog;if(d!=null&&(d.state.equals("active")||d.state.equals("ringing")))d.rtp.send(frame);}
    public Call call(){Dialog d=dialog;return d==null?null:snapshot(d);}
    public RtpEndpoint.Format format(){Dialog d=dialog;return d==null?null:d.rtp.format();}
    private Call snapshot(Dialog d){RtpEndpoint.Format f=d.rtp.format();return new Call(d.id,d.number,d.state,d.incoming,f==null?"":f.name());}
    private void emitCall(Dialog d){listener.call(snapshot(d));}
    private void finishCall(Dialog d){d.rtp.close();d.state="ended";emitCall(d);if(dialog==d)dialog=null;}
    public void close(){closed=true;registered=false;Dialog d=dialog;if(d!=null)finishCall(d);if(tcp!=null)tcp.close();if(tcpListener!=null)tcpListener.close();client.close();server.close();worker.shutdownNow();security.close();}
}
