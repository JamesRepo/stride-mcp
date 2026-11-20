package com.hammez.stride.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Filling in path templates. */
class GarminDataConfigTest {

    @Test
    void fillsOnePart() {
        assertThat(GarminDataConfig.fill("/usersummary-service/usersummary/daily/{displayName}", "jo runner"))
                .isEqualTo("/usersummary-service/usersummary/daily/jo%20runner");
    }

    @Test
    void fillsTwoPartsInOrder() {
        assertThat(GarminDataConfig.fill("/weight-service/weight/range/{from}/{to}", "2026-10-01", "2026-10-30"))
                .isEqualTo("/weight-service/weight/range/2026-10-01/2026-10-30");
    }

    @Test
    void encodesEachPartSoItCantChangeTheAddress() {
        assertThat(GarminDataConfig.fill("/a/{from}/{to}/b", "x/../y", "?q"))
                .isEqualTo("/a/x%2F..%2Fy/%3Fq/b");
    }

    @Test
    void refusesTooFewOrTooManyValues() {
        assertThatThrownBy(() -> GarminDataConfig.fill("/a/{from}/{to}", "2026-10-01"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("More {…} parts than values");
        assertThatThrownBy(() -> GarminDataConfig.fill("/a/{id}", "1", "2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fewer {…} parts than values");
        assertThatThrownBy(() -> GarminDataConfig.fill("/a/b", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Fewer {…} parts than values");
    }
}
