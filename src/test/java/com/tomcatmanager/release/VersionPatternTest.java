package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Automatic version labels built from a pattern. */
class VersionPatternTest {
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    /** 2026-11-16 09:30:05 in Dhaka. */
    private static final Instant NOW = Instant.parse("2026-11-16T03:30:05Z");

    private static VersionPattern.Inputs at(Instant time, String file, String manifest) { return new VersionPattern.Inputs(time, DHAKA, file, manifest); }
    private static VersionPattern.Inputs now() { return at(NOW, null, null); }

    @Test void numbersContinueFromTheHighestExistingVersionOfTheSameShape() {
        assertThat(VersionPattern.next("1.0.{n}", now(), List.of())).isEqualTo("1.0.1");
        assertThat(VersionPattern.next("1.0.{n}", now(), List.of("1.0.1", "1.0.7", "1.0.3", "2.0.9", "1.0.x"))).isEqualTo("1.0.8");
        assertThat(VersionPattern.next("{n}", now(), List.of("1", "2", "1.0.5"))).isEqualTo("3");
    }

    @Test void dateTokensUseTheDisplayTimeZone_andTheNumberRestartsEachDay() {
        List<String> existing = List.of("2026.11.15.4", "2026.11.16.1", "2026.11.16.2");
        assertThat(VersionPattern.next("{yyyy}.{MM}.{dd}.{n}", now(), existing)).isEqualTo("2026.11.16.3");
        assertThat(VersionPattern.next("{yyyy}.{MM}.{dd}.{n}", at(Instant.parse("2026-11-17T01:00:00Z"), null, null), existing))
                .as("a new day starts at 1").isEqualTo("2026.11.17.1");
        assertThat(VersionPattern.next("{yy}{MM}{dd}-{HH}{mm}{ss}", now(), List.of())).isEqualTo("261116-093005");
    }

    @Test void paddingAndCaseInsensitiveMatching() {
        assertThat(VersionPattern.next("build-{n:3}", now(), List.of("BUILD-009"))).isEqualTo("build-010");
        assertThat(VersionPattern.next("build-{n:3}", now(), List.of())).isEqualTo("build-001");
    }

    @Test void theFileAndManifestVersionsComeFromTheWar() {
        assertThat(VersionPattern.next("{file}", at(NOW, "2.4.1", null), List.of())).isEqualTo("2.4.1");
        assertThat(VersionPattern.next("{file}-b{n}", at(NOW, "2.4.1", null), List.of("2.4.1-b1", "2.4.0-b7"))).isEqualTo("2.4.1-b2");
        assertThat(VersionPattern.next("{manifest}", at(NOW, null, "3.0.0"), List.of())).isEqualTo("3.0.0");
        assertThatThrownBy(() -> VersionPattern.next("{file}", now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("file name has none");
        assertThatThrownBy(() -> VersionPattern.next("{manifest}", now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Implementation-Version");
    }

    @Test void aTakenVersionWithoutANumberGetsASuffix() {
        assertThat(VersionPattern.next("{file}", at(NOW, "2.4.1", null), List.of("2.4.1", "2.4.1-2"))).isEqualTo("2.4.1-3");
        assertThat(VersionPattern.next("{yyyy}.{MM}.{dd}", now(), List.of("2026.11.16"))).isEqualTo("2026.11.16-2");
    }

    @Test void invalidPatternsAreExplained() {
        assertThat(VersionPattern.problem("1.0.{n}")).isNull();
        assertThat(VersionPattern.problem("{yyyy}.{MM}.{dd}.{n:2}")).isNull();
        assertThat(VersionPattern.problem("")).contains("Enter a version pattern");
        assertThat(VersionPattern.problem("1.0.0")).contains("at least one placeholder");
        assertThat(VersionPattern.problem("1.0.{build}")).contains("Unknown placeholder {build}");
        assertThat(VersionPattern.problem("v 1.{n}")).contains("letters, digits");
        assertThat(VersionPattern.problem("1.0.{n")).contains("braces");
        assertThat(VersionPattern.problem("-{n}")).contains("start with a letter or digit");
        assertThat(VersionPattern.problem("{yyyy:2}")).contains("Only {n} takes a width");
        assertThat(VersionPattern.problem("x".repeat(61) + "{n}")).contains("at most");
        VersionPattern.PRESETS.keySet().forEach(p -> assertThat(VersionPattern.problem(p)).as(p).isNull());
    }

    @Test void thePreviewShowsTheNextVersionOrPlaceholdersForWarValues() {
        assertThat(VersionPattern.preview("1.0.{n}", DHAKA, List.of("1.0.4"))).isEqualTo("1.0.5");
        assertThat(VersionPattern.preview("{file}-b{n}", DHAKA, List.of())).isEqualTo("‹file version›-b‹next number›");
        assertThat(VersionPattern.preview("1.0.{oops}", DHAKA, List.of())).isEmpty();
    }
}
