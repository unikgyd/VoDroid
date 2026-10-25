package io.vodroid.core;

import java.util.concurrent.TimeUnit;

/** Driven by the sole UDP reader. NAT keepalives do not authenticate the peer. */
final class IkeLiveness {
    enum Action { NONE, PROBE, RETRY, EXPIRED }
    private final long idle,retry;
    private long authenticated,sent;
    private int attempts;
    IkeLiveness(long now){this(now,TimeUnit.SECONDS.toNanos(30),TimeUnit.SECONDS.toNanos(5));}
    IkeLiveness(long now,long idle,long retry){authenticated=now;this.idle=idle;this.retry=retry;}
    void authenticated(long now){authenticated=now;}
    void answered(long now){authenticated=now;attempts=0;}
    Action tick(long now){
        if(attempts>0){if(now-sent<retry)return Action.NONE;if(attempts>=3)return Action.EXPIRED;sent=now;attempts++;return Action.RETRY;}
        if(now-authenticated<idle)return Action.NONE;
        attempts=1;sent=now;return Action.PROBE;
    }
}
