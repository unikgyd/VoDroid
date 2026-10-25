package io.vodroid.core;

import java.io.IOException;
import static io.vodroid.core.Bytes.*;

/** Android telephony P3/data mapping for ISO 7816 short APDUs. */
public record TelephonyApdu(int cla,int ins,int p1,int p2,int p3,String data) {
    public static TelephonyApdu from(byte[] command) throws IOException {
        if(command.length<4||command.length>261)throw new IOException("Only short APDU is supported by telephony transport");
        int p3=command.length>4?u(command[4]):0;
        if(command.length>5&&(p3==0||command.length!=5+p3&&command.length!=6+p3))throw new IOException("Invalid short APDU length");
        // Case 4 includes Le after the data. Android's telephony API needs this
        // byte in its data parameter, as in OpenEUICC's transport adapter.
        return new TelephonyApdu(u(command[0]),u(command[1]),u(command[2]),u(command[3]),p3,command.length>5?hex(cut(command,5,command.length-5)):"");
    }
}
