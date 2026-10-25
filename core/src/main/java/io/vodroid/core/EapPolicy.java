package io.vodroid.core;

import java.util.Locale;

public enum EapPolicy {
    AUTO, AKA, AKA_PRIME;
    public static EapPolicy parse(String value){return switch(value==null?"auto":value.toLowerCase(Locale.ROOT)){
        case "auto",""->AUTO;case "aka"->AKA;case "aka-prime"->AKA_PRIME;default->throw new IllegalArgumentException("Unknown EAP policy");
    };}
    boolean allows(int method){return method==23&&this!=AKA_PRIME||method==50&&this!=AKA;}
    public String identity(String nai){return this==AKA_PRIME&&nai.startsWith("0")?"6"+nai.substring(1):nai;}
}
