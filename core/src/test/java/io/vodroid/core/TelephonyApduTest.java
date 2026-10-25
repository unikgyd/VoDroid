package io.vodroid.core;
import java.io.IOException;
import org.junit.Test;
import static io.vodroid.core.Bytes.*;
import static org.junit.Assert.*;

public class TelephonyApduTest {
    @Test public void preservesResponseLengthInCaseFourAuthentication() throws Exception {
        TelephonyApdu apdu=TelephonyApdu.from(hex("0088008102010200"));
        assertEquals(2,apdu.p3());assertEquals("010200",apdu.data());
        TelephonyApdu read=TelephonyApdu.from(hex("00B0000009"));assertEquals(9,read.p3());assertEquals("",read.data());
        assertEquals("",TelephonyApdu.from(hex("00A40000")).data());
        assertEquals("3F00",TelephonyApdu.from(hex("00A40004023F00")).data());
        assertThrows(IOException.class,()->TelephonyApdu.from(hex("00A400040501")));
    }
}
