package io.vodroid.core;

import java.util.concurrent.*;

/** A queue timeout cancels a queued operation; a started card write must report its real result. */
public final class CardTaskQueue {
    private CardTaskQueue() {}
    public static <T> T call(ExecutorService executor, Callable<T> action, long timeout, TimeUnit unit) throws Exception {
        Object gate=new Object();boolean[] state={false,false}; // started, cancelled before start
        Future<T> task=executor.submit(()->{
            synchronized(gate){if(state[1])throw new CancellationException("Card operation cancelled before start");state[0]=true;}
            return action.call();
        });
        try{return task.get(timeout,unit);}
        catch(TimeoutException timeoutError){
            synchronized(gate){if(!state[0]){state[1]=true;task.cancel(false);throw timeoutError;}}
            // The card operation is already executing. Do not report failure and
            // then allow an installation or profile switch to finish unnoticed.
            return task.get();
        }catch(InterruptedException interrupted){
            synchronized(gate){if(!state[0]){state[1]=true;task.cancel(false);}}
            Thread.currentThread().interrupt();throw interrupted;
        }
    }
}
