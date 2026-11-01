package com.tomcatmanager.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagerUrlTest {
    @Test void parsesFullUrl() {
        ManagerUrl url = ManagerUrl.parse(" https://Tomcat01.example.com:8443/manager/text/ ");
        assertThat(url.protocol()).isEqualTo(TomcatInstance.ManagerProtocol.HTTPS);
        assertThat(url.host()).isEqualTo("Tomcat01.example.com");
        assertThat(url.port()).isEqualTo(8443);
        assertThat(url.path()).isEqualTo("/manager/text");
        assertThat(url.toString()).isEqualTo("https://Tomcat01.example.com:8443/manager/text");
    }

    @Test void appliesDefaults() {
        assertThat(ManagerUrl.parse("http://10.0.0.5").toString()).isEqualTo("http://10.0.0.5:80/manager/text");
        assertThat(ManagerUrl.parse("https://tomcat.example").port()).isEqualTo(443);
        assertThat(ManagerUrl.parse("http://[fe80::1]:8080/manager/text").toString()).isEqualTo("http://[fe80::1]:8080/manager/text");
    }

    @Test void rejectsUnsafeOrInvalidUrls() {
        assertThatThrownBy(() -> ManagerUrl.parse("ftp://host/manager/text")).hasMessageContaining("https://");
        assertThatThrownBy(() -> ManagerUrl.parse("https://admin:secret@host/manager/text")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> ManagerUrl.parse("https://host/manager/text?x=1")).hasMessageContaining("? or #");
        assertThatThrownBy(() -> ManagerUrl.parse("tomcat01:8443")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagerUrl.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void storedInstanceFallsBackToHostAddress() {
        ManagedHost host = new ManagedHost(); host.setHostname("10.1.1.1");
        TomcatInstance i = new TomcatInstance(); i.setHost(host);
        i.setManagerProtocol(TomcatInstance.ManagerProtocol.HTTPS); i.setManagerPort(8443); i.setManagerEndpoint("/manager/text");
        assertThat(ManagerUrl.of(i).toString()).isEqualTo("https://10.1.1.1:8443/manager/text");
    }
}
