package com.tomcatmanager.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureValidationTest {
    @Test void hostnames() {
        assertThat(InfrastructureValidation.isValidHostname("tomcat-prod-01.example.com")).isTrue();
        assertThat(InfrastructureValidation.isValidHostname("10.0.0.15")).isTrue();
        assertThat(InfrastructureValidation.isValidHostname("fe80::1")).isTrue();
        assertThat(InfrastructureValidation.isValidHostname("999.1.1.1")).isFalse();
        assertThat(InfrastructureValidation.isValidHostname("host name")).isFalse();
        assertThat(InfrastructureValidation.isValidHostname("-bad.example")).isFalse();
        assertThat(InfrastructureValidation.isValidHostname("http://host")).isFalse();
        assertThat(InfrastructureValidation.isValidHostname("host;rm -rf /")).isFalse();
    }

    @Test void remotePaths() {
        assertThat(InfrastructureValidation.isSafeAbsolutePath("/opt/tomcat/instance-a")).isTrue();
        assertThat(InfrastructureValidation.isSafeAbsolutePath("opt/tomcat")).isFalse();
        assertThat(InfrastructureValidation.isSafeAbsolutePath("/opt/../etc")).isFalse();
        assertThat(InfrastructureValidation.isSafeAbsolutePath("/opt/tomcat; reboot")).isFalse();
        assertThat(InfrastructureValidation.isSafeAbsolutePath("/opt/$(id)")).isFalse();
        assertThat(InfrastructureValidation.isSafePath("webapps")).isTrue();
    }

    @Test void managerPathsAndUsernames() {
        assertThat(InfrastructureValidation.isValidUrlPath("/manager/text")).isTrue();
        assertThat(InfrastructureValidation.isValidUrlPath("/manager/text?x=1")).isFalse();
        assertThat(InfrastructureValidation.isValidUrlPath("manager/text")).isFalse();
        assertThat(InfrastructureValidation.isValidPosixUsername("deploy")).isTrue();
        assertThat(InfrastructureValidation.isValidPosixUsername("deploy user")).isFalse();
    }

    @Test void deploymentDirectoryIsDerivedFromCatalinaBase() {
        TomcatInstance i = new TomcatInstance();
        i.setCatalinaBase("/opt/tomcat/a"); i.setAppBase("webapps");
        assertThat(i.getRemoteDeploymentDirectory()).isEqualTo("/opt/tomcat/a/webapps");
        i.setAppBase("/srv/apps");
        assertThat(i.getRemoteDeploymentDirectory()).isEqualTo("/srv/apps");
    }
}
