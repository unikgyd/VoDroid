package io.vodroid.core;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.vodroid.core.IkeLiveness.Action.*;

public class IkeLivenessTest {
    @Test public void authenticatedTrafficDefersProbeAndMatchingReplyClearsIt(){
        IkeLiveness life=new IkeLiveness(0,30,5);assertEquals(NONE,life.tick(29));life.authenticated(29);assertEquals(NONE,life.tick(30));
        assertEquals(PROBE,life.tick(59));assertEquals(NONE,life.tick(63));assertEquals(RETRY,life.tick(64));life.answered(65);
        assertEquals(NONE,life.tick(94));assertEquals(PROBE,life.tick(95));
    }
    @Test public void probeRetransmissionsAreBoundedAndEspDoesNotAcknowledgeAnIkeRequest(){
        IkeLiveness life=new IkeLiveness(0,30,5);assertEquals(PROBE,life.tick(30));life.authenticated(31);
        assertEquals(RETRY,life.tick(35));assertEquals(RETRY,life.tick(40));assertEquals(EXPIRED,life.tick(45));
    }
}
