package io.vodroid.core;

import java.io.IOException;
import java.util.*;
import static io.vodroid.core.Bytes.*;

/** CREATE_CHILD_SA proposals: IKE SPIs are eight bytes, ESP SPIs four. */
final class IkeRekeyProposal {
    record Choice(int number,byte[] spi,IkeSuite suite,int group){}
    private record Transform(int id,int bits){}
    private record Proposal(int number,byte[] spi,Map<Integer,List<Transform>> transforms){}
    private IkeRekeyProposal(){}
    static byte[] encode(Choice choice,boolean ike,byte[] spi){
        IkeSuite s=choice.suite();List<byte[]> ts=new ArrayList<>();ts.add(t(1,12,s.encryptionBits()));
        if(ike)ts.add(t(2,s.prfId(),0));ts.add(t(3,s.integrityId(),0));
        if(ike||choice.group()!=0)ts.add(t(4,choice.group(),0));if(!ike)ts.add(t(5,0,0));
        byte[] body=new byte[0];for(int n=0;n<ts.size();n++){byte[] transform=ts.get(n);transform[0]=(byte)(n+1==ts.size()?0:3);body=cat(body,transform);}
        return cat(b(0,0),n16(8+spi.length+body.length),b(choice.number(),ike?1:3,spi.length,ts.size()),spi,body);
    }
    private static byte[] t(int type,int id,int bits){return cat(b(0,0),n16(bits==0?8:12),b(type,0),n16(id),bits==0?new byte[0]:cat(n16(0x800e),n16(bits)));}
    static Choice choose(byte[] sa,boolean ike,int group,IkeSuite preferred)throws IOException{
        for(Proposal p:parse(sa,ike)){
            Map<Integer,List<Transform>> ts=p.transforms();
            if(ts.keySet().stream().anyMatch(type->!Set.of(1,2,3,4,5).contains(type))||!ike&&ts.containsKey(2)||ike&&ts.containsKey(5))continue;
            if(ike||group!=0){if(!has(ts,4,group,0))continue;}else if(ts.containsKey(4)&&!has(ts,4,0,0))continue;
            if(!ike&&ts.containsKey(5)&&!has(ts,5,0,0))continue;
            int bits=first(ts,1,12,new int[]{preferred.encryptionBits(),preferred.encryptionBits()==128?256:128},true);
            int mac=first(ts,3,0,new int[]{preferred.integrityId(),preferred.integrityId()==2?12:2},false);
            int prf=ike?first(ts,2,0,new int[]{preferred.prfId(),preferred.prfId()==2?5:2},false):0;
            if(bits!=0&&mac!=0&&(!ike||prf!=0))return new Choice(p.number(),p.spi(),new IkeSuite(bits,prf,mac,ike?group:0),group);
        }
        throw new IOException("No compatible rekey proposal");
    }
    static Choice selected(byte[] sa,boolean ike,int group,IkeSuite offered)throws IOException{
        List<Proposal> ps=parse(sa,ike);if(ps.size()!=1||ps.get(0).number()!=1)throw new IOException("Invalid rekey selection");
        for(List<Transform> ts:ps.get(0).transforms().values())if(ts.size()!=1)throw new IOException("Multiple selected rekey transforms");
        Choice result=choose(sa,ike,group,offered);
        if(!result.suite().equals(offered))throw new IOException("Unoffered rekey algorithms selected");return result;
    }
    private static int first(Map<Integer,List<Transform>> ts,int type,int id,int[] choices,boolean bits){for(int choice:choices)if(has(ts,type,bits?id:choice,bits?choice:0))return choice;return 0;}
    private static boolean has(Map<Integer,List<Transform>> ts,int type,int id,int bits){return ts.getOrDefault(type,List.of()).contains(new Transform(id,bits));}
    private static List<Proposal> parse(byte[] sa,boolean ike)throws IOException{
        if(sa==null||sa.length<8)throw new IOException("Missing rekey SA");List<Proposal> result=new ArrayList<>();Set<Integer> numbers=new HashSet<>();
        for(int p=0;p<sa.length;){range(sa,p,8);int length=u16(sa,p+2),spi=u(sa[p+6]),number=u(sa[p+4]);
            if(result.size()>=8||length<8+spi||p+length>sa.length||u(sa[p])!=(p+length==sa.length?0:2)||u(sa[p+5])!=(ike?1:3)||spi!=(ike?8:4)||number==0||!numbers.add(number))throw new IOException("Invalid rekey proposal header");
            byte[] value=cut(sa,p+8,spi);if(Arrays.equals(value,new byte[spi]))throw new IOException("Zero rekey SPI");
            Map<Integer,List<Transform>> transforms=new HashMap<>();int count=0;
            for(int t=p+8+spi;t<p+length;){range(sa,t,8);int size=u16(sa,t+2),type=u(sa[t+4]),id=u16(sa,t+6),bits=0;
                if(++count>64||size<8||t+size>p+length||u(sa[t])!=(t+size==p+length?0:3))throw new IOException("Invalid rekey transform");
                if(size==12&&type==1&&u16(sa,t+8)==0x800e)bits=u16(sa,t+10);else if(size!=8)throw new IOException("Unsupported rekey transform attributes");
                transforms.computeIfAbsent(type,k->new ArrayList<>()).add(new Transform(id,bits));t+=size;
            }
            if(count!=u(sa[p+7]))throw new IOException("Rekey transform count mismatch");result.add(new Proposal(number,value,transforms));p+=length;
        }return result;
    }
}
