package com.hammez.stride.garmin;

import java.util.List;
import java.util.Optional;

/**
 * A day's weight and body composition, picked from its weigh-ins: the latest weight, and the latest body
 * composition. These can come from different weigh-ins: a scale in the morning (with body fat and so on) and a
 * weight typed in later. Shared by the CLI's {@code summary} and the MCP tools, so both pick the same ones.
 *
 * @param weight          the latest weigh-in with a weight, or null if none had one
 * @param bodyComposition the latest weigh-in with any body composition value, or null if none had one
 * @param weighIns        how many weigh-ins the day had
 */
public record DayWeight(WeighIn weight, WeighIn bodyComposition, int weighIns) {

    /**
     * @param weighIns oldest first, as {@link GarminClient#weighIns} returns them
     * @return empty if no weigh-in had a weight or any body composition
     */
    public static Optional<DayWeight> of(List<WeighIn> weighIns) {
        WeighIn weight = null;
        WeighIn body = null;
        for (WeighIn w : weighIns) {
            if (w.weightKilograms() != null) {
                weight = w;
            }
            if (w.hasBodyComposition()) {
                body = w;
            }
        }
        if (weight == null && body == null) {
            return Optional.empty();
        }
        return Optional.of(new DayWeight(weight, body, weighIns.size()));
    }

    /** True if the weight and the body composition come from two different weigh-ins. */
    public boolean fromDifferentWeighIns() {
        return weight != null && bodyComposition != null && weight != bodyComposition;
    }
}
