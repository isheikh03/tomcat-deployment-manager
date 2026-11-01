package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseInputRulesTest {
    @Test void filenamesAreReducedToSafeBaseNames() {
        assertThat(ReleaseService.sanitizeFilename("C:\\Users\\me\\Downloads\\portal-2.4.1.war")).isEqualTo("portal-2.4.1.war");
        assertThat(ReleaseService.sanitizeFilename("/tmp/build/portal.WAR")).isEqualTo("portal.WAR");
        assertThatThrownBy(() -> ReleaseService.sanitizeFilename("portal.zip")).hasMessageContaining(".war");
        assertThatThrownBy(() -> ReleaseService.sanitizeFilename("../../x/;rm -rf.war")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseService.sanitizeFilename("my app.war")).hasMessageContaining("Rename");
        assertThatThrownBy(() -> ReleaseService.sanitizeFilename(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void versionLabels() {
        assertThat(ReleaseService.validateLabel(" 2.4.1-RC1 ")).isEqualTo("2.4.1-RC1");
        assertThat(ReleaseService.validateLabel("2024.10.28+build.7")).isEqualTo("2024.10.28+build.7");
        assertThatThrownBy(() -> ReleaseService.validateLabel("")).hasMessageContaining("Enter a version");
        assertThatThrownBy(() -> ReleaseService.validateLabel("1.0 final")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseService.validateLabel("-1.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseService.validateLabel("<script>")).isInstanceOf(IllegalArgumentException.class);
    }
}
