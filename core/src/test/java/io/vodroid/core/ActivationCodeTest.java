package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivationCodeTest {
    @Test public void acceptsRawAndLpaWithoutChangingTokens(){
        assertEquals("AB+C",ActivationCode.parse(" lpa:1$smdp.example$AB+C ").matchingId());
        assertEquals("LPA:1$smdp.example$abc",ActivationCode.parse("1$smdp.example$abc").canonical());
    }
    @Test public void decodesInstallationLinks(){
        assertEquals("AB+C",ActivationCode.parse("https://esimsetup.android.com/esim_qrcode_provisioning?carddata=LPA%3A1%24smdp.example%24AB%2BC").matchingId());
        assertEquals("abc",ActivationCode.parse("LPA:1%24smdp.example%24abc").matchingId());
    }
    @Test public void retainsOptionalFieldsAndEmptyMatchingId(){
        assertEquals("1$smdp.example$$1.2.3$1",ActivationCode.parse("LPA:1$smdp.example$$1.2.3$1").payload());
    }
    @Test public void rejectsMalformedInputsWithoutLeakingThem(){
        for(String input:new String[]{"secret","https://smdp.example/profile","LPA:1$$secret","LPA:2$smdp.example$secret","LPA:1$smdp.example/path$secret","LPA:1$user@smdp.example$secret","LPA:1$smdp.example$sec\nret","LPA:1$smdp.example$secret$bad","https://example/?carddata=one&carddata=two"}){
            IllegalArgumentException e=assertThrows(IllegalArgumentException.class,()->ActivationCode.parse(input));assertFalse(e.getMessage().contains("secret"));
        }
    }
    @Test public void diagnosticRepresentationIsRedacted(){assertFalse(ActivationCode.parse("1$smdp.example$private-token").toString().contains("private-token"));}
}
