package com.tomcatmanager.connectivity;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivityUtilitiesTest {

    @Test void sanitizerRedactsSecretsAndStripsControlCharacters() {
        String raw = "login failed password=hunter2\r\nAuthorization: Basic dXNlcjpwYXNz\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY----- at https://admin:pw@host/x";
        String clean = DiagnosticSanitizer.clean(raw);
        assertThat(clean).doesNotContain("hunter2", "dXNlcjpwYXNz", "abc", "admin:pw", "\n", "\r")
                .contains("password=[REDACTED]", "[REDACTED PRIVATE KEY]", "https://[REDACTED]@host");
    }

    @Test void sanitizerBoundsLength() {
        assertThat(DiagnosticSanitizer.clean("x".repeat(2000))).hasSize(DiagnosticSanitizer.DEFAULT_MAX_LENGTH);
    }

    @Test void fingerprintsAreNormalizedAndValidated() {
        String fp = "SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8";
        assertThat(HostKeyFingerprints.isValid(fp)).isTrue();
        assertThat(HostKeyFingerprints.isValid(" sha256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8= ")).isTrue();
        assertThat(HostKeyFingerprints.matches(fp, "sha256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8=")).isTrue();
        assertThat(HostKeyFingerprints.isValid("MD5:16:27:ac:a5:76:28:2d:36:63:1b:56:4d:eb:df:a6:48")).isFalse();
        assertThat(HostKeyFingerprints.isValid("SHA256:short")).isFalse();
        assertThat(HostKeyFingerprints.matches(null, fp)).isFalse();
    }

    @Test void directoryPermissionsFollowPosixRules() {
        assertThat(DirectoryPermissions.evaluate(0755, 1000, 1000, 1000, Set.of(1000))).isEqualTo(DirectoryPermissions.Access.WRITABLE);
        assertThat(DirectoryPermissions.evaluate(0755, 0, 0, 1000, Set.of(1000))).isEqualTo(DirectoryPermissions.Access.NOT_WRITABLE);
        assertThat(DirectoryPermissions.evaluate(0775, 0, 27, 1000, Set.of(1000, 27))).isEqualTo(DirectoryPermissions.Access.WRITABLE);
        assertThat(DirectoryPermissions.evaluate(0760, 0, 27, 1000, Set.of(27))).isEqualTo(DirectoryPermissions.Access.NOT_WRITABLE);
        assertThat(DirectoryPermissions.evaluate(0700, 0, 0, 0, Set.of(0))).isEqualTo(DirectoryPermissions.Access.WRITABLE);
        assertThat(DirectoryPermissions.evaluate(0777, 0, 0, 1000, Set.of())).isEqualTo(DirectoryPermissions.Access.WRITABLE);
        assertThat(DirectoryPermissions.symbolic(0750)).isEqualTo("rwxr-x---");
    }

    @Test void reportOutcomeIsWorstStatusAndChecksAreOrdered() {
        ProbeReport report = new ProbeReport();
        report.notApplicable(CheckType.MANAGER_ENDPOINT);
        report.success(CheckType.NETWORK_TARGET, "ok");
        assertThat(report.outcome()).isEqualTo(CheckStatus.SUCCESS);
        report.warning(CheckType.DEPLOYMENT_DIRECTORY, "hmm");
        assertThat(report.outcome()).isEqualTo(CheckStatus.WARNING);
        report.failed(CheckType.SSH_CONNECTION, "no");
        assertThat(report.outcome()).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).extracting(ConnectionCheck::getCheckType)
                .containsExactly(CheckType.NETWORK_TARGET, CheckType.SSH_CONNECTION, CheckType.DEPLOYMENT_DIRECTORY, CheckType.MANAGER_ENDPOINT);
    }
}
