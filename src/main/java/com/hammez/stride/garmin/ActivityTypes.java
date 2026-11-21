package com.hammez.stride.garmin;

import java.util.Map;
import java.util.Set;

/**
 * Each activity type's group, from Garmin's list of activity types (phase 4b). The list is a tree: every type has a
 * {@code parentTypeId}, and the root ({@code all}, seen as typeId 17 on 20 November 2026) has none. A type's group is
 * its ancestor just below the root, or the type itself if its parent is the root. So {@code running},
 * {@code trail_running} and {@code treadmill_running} are all {@code running}, and {@code strength_training} is
 * {@code fitness_equipment}.
 *
 * @param groups each type key's group. A type whose parents can't be followed up to the root, and the root itself,
 *               aren't in it.
 * @param listed every type key in Garmin's list, with a group or not
 */
public record ActivityTypes(Map<String, String> groups, Set<String> listed) {

    public ActivityTypes {
        groups = Map.copyOf(groups);
        listed = Set.copyOf(listed);
    }

    /** The type's group, or null if the type isn't in Garmin's list (or is null). */
    public String group(String typeKey) {
        return typeKey == null ? null : groups.get(typeKey);
    }

    /** True if Garmin's list has this type, even if it has no group. */
    public boolean lists(String typeKey) {
        return typeKey != null && listed.contains(typeKey);
    }
}
