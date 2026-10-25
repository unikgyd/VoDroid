package io.vodroid.core;

/** Routing hints from an exactly matched Android subscription, or 3GPP defaults. */
public record CarrierProfile(String id, String epdg, boolean legacy,IkeTiming timing) {
    public CarrierProfile(String id,String epdg,boolean legacy){this(id,epdg,legacy,IkeTiming.DEFAULT);}
    public CarrierProfile {java.util.Objects.requireNonNull(timing);}
    public static final CarrierProfile STANDARD = new CarrierProfile("standard-3gpp", "", false);
}
