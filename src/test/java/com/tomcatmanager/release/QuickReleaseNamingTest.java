package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QuickReleaseNamingTest {
    @TempDir Path dir;

    @Test void applicationAndVersionComeFromTheFileName() {
        assertThat(QuickReleaseNaming.applicationName("customer-portal-2.4.1.war")).isEqualTo("customer-portal");
        assertThat(QuickReleaseNaming.versionFromFilename("customer-portal-2.4.1.war")).isEqualTo("2.4.1");
        assertThat(QuickReleaseNaming.applicationName("app-1.0-SNAPSHOT.war")).isEqualTo("app");
        assertThat(QuickReleaseNaming.versionFromFilename("app-1.0-SNAPSHOT.war")).isEqualTo("1.0-SNAPSHOT");
        assertThat(QuickReleaseNaming.applicationName("billing_v3.2.WAR")).isEqualTo("billing");
        assertThat(QuickReleaseNaming.versionFromFilename("billing_v3.2.WAR")).isEqualTo("3.2");
        assertThat(QuickReleaseNaming.versionFromFilename("report-2024-01-05.war")).isEqualTo("2024-01-05");
    }

    @Test void tomcatParallelDeploymentNamesAreUnderstood() {
        assertThat(QuickReleaseNaming.applicationName("shop##1.2.war")).isEqualTo("shop");
        assertThat(QuickReleaseNaming.versionFromFilename("shop##1.2.war")).isEqualTo("1.2");
    }

    @Test void namesWithoutAVersionKeepTheWholeName() {
        assertThat(QuickReleaseNaming.applicationName("ROOT.war")).isEqualTo("ROOT");
        assertThat(QuickReleaseNaming.versionFromFilename("ROOT.war")).isNull();
        assertThat(QuickReleaseNaming.applicationName("app2.war")).isEqualTo("app2");
        assertThat(QuickReleaseNaming.versionFromFilename("app2.war")).isNull();
        assertThat(QuickReleaseNaming.applicationName("C:\\builds\\portal-1.0.war")).isEqualTo("portal");
    }

    @Test void versionFallsBackToTheManifestThenTheUploadTime() throws Exception {
        Path withManifest = TestWars.war().entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 3.1.4\r\n\r\n")
                .jakartaWebXml().writeTo(dir.resolve("a.war"));
        assertThat(QuickReleaseNaming.versionLabel("portal.war", withManifest)).isEqualTo("3.1.4");
        assertThat(QuickReleaseNaming.versionLabel("portal-2.0.war", withManifest)).isEqualTo("2.0");

        Path plain = TestWars.war().jakartaWebXml().writeTo(dir.resolve("b.war"));
        assertThat(QuickReleaseNaming.versionLabel("portal.war", plain)).matches("quick-\\d{8}-\\d{6}");
        Path notAZip = Files.writeString(dir.resolve("c.war"), "not a zip");
        assertThat(QuickReleaseNaming.versionFromManifest(notAZip)).isNull();
    }

    @Test void deploymentFilenameIsSafe() {
        assertThat(QuickReleaseNaming.deploymentFilename("customer-portal")).isEqualTo("customer-portal.war");
        assertThat(QuickReleaseNaming.deploymentFilename("Customer Portal")).isEqualTo("Customer-Portal.war");
        assertThat(QuickReleaseNaming.deploymentFilename("--- ")).isEqualTo("app.war");
    }
}
