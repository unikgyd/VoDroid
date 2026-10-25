package io.vodroid.core;

/** Validated carrier lifetimes; malformed hints cannot disable expiry or cause a rekey loop. */
public record IkeTiming(int ikeSoft,int ikeHard,int childSoft,int childHard,boolean childPfs) {
    public static final IkeTiming DEFAULT=new IkeTiming(3600,7200,1800,3600,false);
    public IkeTiming {if(!valid(ikeSoft,ikeHard)||!valid(childSoft,childHard))throw new IllegalArgumentException("Invalid SA lifetimes");}
    private static boolean valid(int soft,int hard){return soft>=60&&hard>=180&&hard<=86400&&soft<=hard-60;}
    public static IkeTiming hints(int ikeSoft,int ikeHard,int childSoft,int childHard,boolean pfs){
        if(!valid(ikeSoft,ikeHard)){ikeSoft=DEFAULT.ikeSoft;ikeHard=DEFAULT.ikeHard;}
        if(!valid(childSoft,childHard)){childSoft=DEFAULT.childSoft;childHard=DEFAULT.childHard;}
        return new IkeTiming(ikeSoft,ikeHard,childSoft,childHard,pfs);
    }
}
