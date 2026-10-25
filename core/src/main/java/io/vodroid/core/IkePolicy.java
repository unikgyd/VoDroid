package io.vodroid.core;

import java.util.Locale;

/** VoCat-compatible presets; 'standard' describes a carrier profile, not a cipher. */
public enum IkePolicy {
    AUTO, MODERN, LEGACY;

    public static IkePolicy parse(String value) {
        if (value == null || value.isBlank()) return AUTO;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "modern" -> MODERN;
            case "legacy", "legacy-sha1-modp1024" -> LEGACY;
            default -> throw new IllegalArgumentException("Unknown IKE policy");
        };
    }

    public int initialGroup(CarrierProfile profile) {
        return this == LEGACY || this == AUTO && profile.legacy() ? 2 : 14;
    }

    public boolean allowsLegacyFallback(int currentGroup) {
        return this == AUTO && currentGroup == 14;
    }
}
