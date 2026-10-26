package com.hammez.stride.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The units at their rounding edges. A missing value is null: the CLI and the tools each show it their way. */
class UnitsTest {

    @Test
    void durationsRoundToTheSecondAndShowHoursOnlyWhenThereAreSome() {
        assertThat(Units.duration(59.4)).isEqualTo("0:59");
        assertThat(Units.duration(59.6)).isEqualTo("1:00");
        assertThat(Units.duration(3599.4)).isEqualTo("59:59");
        assertThat(Units.duration(3599.6)).isEqualTo("1:00:00");
        assertThat(Units.duration(36_005.0)).isEqualTo("10:00:05");
        assertThat(Units.duration(null)).isNull();
    }

    @Test
    void sleepDurationsAlwaysShowHours() {
        assertThat(Units.hours(480)).isEqualTo("0:08:00");
        assertThat(Units.hours(31_920)).isEqualTo("8:52:00");
        assertThat(Units.hours(null)).isNull();
    }

    @Test
    void paceIsMinutesAndSecondsPerKilometre() {
        assertThat(Units.pace(2.338)).isEqualTo("7:08 /km");
        assertThat(Units.pace(1000 / 299.6)).isEqualTo("5:00 /km");
        assertThat(Units.pace(1000 / 59.6)).isEqualTo("1:00 /km");
        assertThat(Units.pace(0.5)).isEqualTo("33:20 /km");
        assertThat(Units.pace(0.0)).isNull();
        assertThat(Units.pace(-1.0)).isNull();
        assertThat(Units.pace(null)).isNull();
    }

    @Test
    void zeroOrLessIsNotAValue() {
        assertThat(Units.positive(0.0)).isNull();
        assertThat(Units.positive(-0.5)).isNull();
        assertThat(Units.positive(null)).isNull();
        assertThat(Units.positive(0.1)).isEqualTo(0.1);
    }

    @Test
    void garminsLabelsReadAsWords() {
        assertThat(Units.label("EXCELLENT")).isEqualTo("Excellent");
        assertThat(Units.label("VERY_GOOD")).isEqualTo("Very good");
        assertThat(Units.label("")).isNull();
        assertThat(Units.label(null)).isNull();
    }
}
