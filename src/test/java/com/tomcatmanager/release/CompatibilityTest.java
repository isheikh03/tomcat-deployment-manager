package com.tomcatmanager.release;

import com.tomcatmanager.application.ManagedApplication;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityTest {
    private static StoredArtifact artifact(Integer java, StoredArtifact.ServletApi api) {
        return new StoredArtifact("a".repeat(64), 10, "sha256/aa/aa/x.war", new WarInspection(3, true, java, api, List.of()));
    }

    private static ManagedApplication app(ManagedApplication.ServletApi api, Integer minJava, Integer maxJava, String tomcat) {
        ManagedApplication a = new ManagedApplication();
        a.setServletApi(api); a.setMinJavaVersion(minJava); a.setMaxJavaVersion(maxJava); a.setSupportedTomcatVersions(tomcat);
        return a;
    }

    @Test void compatibleWhenEverythingIsKnownAndMatches() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.DETECT, null, null, null), artifact(17, StoredArtifact.ServletApi.JAKARTA));
        var result = Compatibility.evaluate(req, "10.1.30", 21);
        assertThat(result.status()).isEqualTo(Compatibility.Status.COMPATIBLE);
    }

    @Test void jakartaOnTomcat9IsIncompatible() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.DETECT, null, null, null), artifact(11, StoredArtifact.ServletApi.JAKARTA));
        var result = Compatibility.evaluate(req, "9.0.98", 17);
        assertThat(result.status()).isEqualTo(Compatibility.Status.INCOMPATIBLE);
        assertThat(result.reasons()).anySatisfy(r -> assertThat(r).contains("jakarta.servlet"));
    }

    @Test void javaxOnTomcat10IsIncompatible() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.JAVAX, null, null, null), artifact(8, StoredArtifact.ServletApi.UNKNOWN));
        assertThat(Compatibility.evaluate(req, "10.1", 17).status()).isEqualTo(Compatibility.Status.INCOMPATIBLE);
    }

    @Test void detectedClassLevelRaisesTheJavaMinimum() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.JAKARTA, 11, null, null), artifact(21, StoredArtifact.ServletApi.JAKARTA));
        assertThat(req.minJava()).isEqualTo(21);
        assertThat(req.minJavaDetected()).isTrue();
        var result = Compatibility.evaluate(req, "11.0.2", 17);
        assertThat(result.status()).isEqualTo(Compatibility.Status.INCOMPATIBLE);
        assertThat(result.reasons()).anySatisfy(r -> assertThat(r).contains("Needs Java 21+").contains("detected"));
    }

    @Test void maximumJavaAndSupportedTomcatLinesAreEnforced() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.JAKARTA, 17, 17, "10.1"), artifact(17, StoredArtifact.ServletApi.JAKARTA));
        assertThat(Compatibility.evaluate(req, "10.1.30", 21).reasons()).anySatisfy(r -> assertThat(r).contains("up to 17"));
        assertThat(Compatibility.evaluate(req, "11.0.1", 17).reasons()).anySatisfy(r -> assertThat(r).contains("not in the supported versions"));
        assertThat(Compatibility.evaluate(req, "10.1.30", 17).status()).isEqualTo(Compatibility.Status.COMPATIBLE);
    }

    @Test void missingInformationIsUnknownNeverCompatible() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.DETECT, null, null, null), artifact(17, StoredArtifact.ServletApi.JAKARTA));
        assertThat(Compatibility.evaluate(req, null, null).status()).isEqualTo(Compatibility.Status.UNKNOWN);
        assertThat(Compatibility.evaluate(req, "10.1", null).status()).isEqualTo(Compatibility.Status.UNKNOWN);

        var unknownApi = Compatibility.requirements(app(ManagedApplication.ServletApi.DETECT, null, null, null), artifact(null, StoredArtifact.ServletApi.UNKNOWN));
        var result = Compatibility.evaluate(unknownApi, "10.1", 21);
        assertThat(result.status()).isEqualTo(Compatibility.Status.UNKNOWN);
        assertThat(result.reasons()).hasSize(2);
    }

    @Test void knownConflictWinsOverUnknownParts() {
        var req = Compatibility.requirements(app(ManagedApplication.ServletApi.JAKARTA, null, null, null), artifact(null, StoredArtifact.ServletApi.UNKNOWN));
        assertThat(Compatibility.evaluate(req, "9.0", null).status()).isEqualTo(Compatibility.Status.INCOMPATIBLE);
    }
}
