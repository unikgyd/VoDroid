package io.vodroid.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static io.vodroid.core.Bytes.*;
import static org.junit.Assert.*;

public class CardDiagnosticsTest {
    @Test public void probeOnlySelectsAndReadsAndClosesChannel()throws Exception{
        List<String> commands=new ArrayList<>();List<Integer> closed=new ArrayList<>();
        SimCard.Transport transport=new SimCard.Transport(){public int open(byte[] aid){return 3;}public byte[] transmit(int channel,byte[] command){assertEquals(3,channel);commands.add(hex(command));return commands.size()==1?hex("9000"):hex("0819325400214365879000");}public void closeChannel(int channel){closed.add(channel);}public void close(){}};
        SimReadProbe.Result result=SimReadProbe.run(transport,"A0000000871002");assertEquals(0x9000,result.status());assertEquals(9,result.bytes());
        assertEquals(List.of("00A40004026F07","00B0000009"),commands);assertEquals(List.of(3),closed);
    }
    @Test public void deniedReadStillClosesChannel()throws Exception{
        List<Integer> closed=new ArrayList<>();SimCard.Transport transport=new SimCard.Transport(){public int open(byte[] aid){return 1;}public byte[] transmit(int channel,byte[] command){return hex("6982");}public void closeChannel(int channel){closed.add(channel);}public void close(){}};
        assertThrows(java.io.IOException.class,()->SimReadProbe.run(transport,"A0000000871002"));assertEquals(List.of(1),closed);
    }
    @Test public void timedOutQueuedCardTaskNeverRunsLater()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch release=new CountDownLatch(1);AtomicBoolean ran=new AtomicBoolean();
        try{executor.submit(()->{try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertThrows(TimeoutException.class,()->CardTaskQueue.call(executor,()->{ran.set(true);return true;},20,TimeUnit.MILLISECONDS));
            release.countDown();executor.submit(()->{}).get(1,TimeUnit.SECONDS);assertFalse(ran.get());
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test public void startedTaskReturnsActualResultAfterQueueTimeout()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor(),caller=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try{Future<Integer> result=caller.submit(()->CardTaskQueue.call(executor,()->{started.countDown();release.await();return 7;},50,TimeUnit.MILLISECONDS));
            assertTrue(started.await(1,TimeUnit.SECONDS));Thread.sleep(100);assertFalse(result.isDone());release.countDown();assertEquals(Integer.valueOf(7),result.get(1,TimeUnit.SECONDS));
        }finally{release.countDown();executor.shutdownNow();caller.shutdownNow();}
    }
}
