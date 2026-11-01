package com.tomcatmanager.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthExpectationTest {
    @Test void defaultAcceptsAny2xxOnly() {
        HealthExpectation e = HealthExpectation.of(null, null);
        assertThat(e.statusAccepted(200)).isTrue();
        assertThat(e.statusAccepted(299)).isTrue();
        assertThat(e.statusAccepted(302)).as("redirects are not healthy unless accepted").isFalse();
        assertThat(e.statusAccepted(503)).isFalse();
        assertThat(e.isDefault()).isTrue();
    }

    @Test void listsAndRangesAreSupported() {
        HealthExpectation e = HealthExpectation.of("200, 204,300-302", "UP");
        assertThat(e.statusAccepted(204)).isTrue();
        assertThat(e.statusAccepted(201)).isFalse();
        assertThat(e.statusAccepted(301)).isTrue();
        assertThat(e.describe()).isEqualTo("HTTP 200,204,300-302 containing \"UP\"");
    }

    @Test void invalidSpecificationsAreRejectedWithAClearMessage() {
        assertThat(HealthExpectation.statusProblem("")).isNull();
        assertThat(HealthExpectation.statusProblem("200-299")).isNull();
        assertThat(HealthExpectation.statusProblem("ok")).contains("e.g. 200-299");
        assertThat(HealthExpectation.statusProblem("299-200")).contains("must not be larger");
        assertThat(HealthExpectation.statusProblem("100-200")).contains("1xx");
        assertThat(HealthExpectation.statusProblem("200-700")).contains("between 100 and 599");
    }
}
