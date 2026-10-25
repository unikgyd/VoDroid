package io.vodroid.core;

import java.io.IOException;
import java.util.Arrays;
import static io.vodroid.core.Bytes.*;

/** SELECT + READ BINARY only. Return proof of access without returning subscriber data. */
public final class SimReadProbe {
    public record Result(int status, int bytes) {}
    private SimReadProbe() {}
    public static Result run(SimCard.Transport transport, String aid) throws Exception {
        try (SimCard sim = new SimCard(transport)) {
            sim.connect(aid);
            sim.command(hex("00A40004026F07"));
            byte[] response = sim.command(hex("00B0000009"));
            try {
                if(response.length<2 || u(response[0])+1>response.length)throw new IOException("Invalid EF IMSI response");
                int digits=bcd(cut(response,1,u(response[0]))).length()-1;
                if(digits!=14 && digits!=15)throw new IOException("Invalid EF IMSI length");
                return new Result(0x9000,response.length);
            } finally { Arrays.fill(response,(byte)0); }
        }
    }
}
