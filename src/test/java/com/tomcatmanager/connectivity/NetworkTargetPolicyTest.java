package com.tomcatmanager.connectivity;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkTargetPolicyTest {
    private static NetworkTargetPolicy policy(boolean loopback, boolean privateNets, List<String> allowed, List<String> denied) {
        return new NetworkTargetPolicy(new ConnectivityProperties(15, 2, 4, Duration.ofHours(24), Duration.ofHours(1),
                new ConnectivityProperties.TargetPolicy(loopback, false, privateNets, allowed, denied),
                new ConnectivityProperties.Tls(null, null, "PKCS12")));
    }

    @Test void loopbackIsRefusedByDefault() {
        assertThatThrownBy(() -> policy(false, true, List.of(), List.of()).resolve("127.0.0.1"))
                .isInstanceOf(TargetNotAllowedException.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> policy(false, true, List.of(), List.of()).resolve("::1")).isInstanceOf(TargetNotAllowedException.class);
    }

    @Test void cloudMetadataAndWildcardAreRefused() {
        NetworkTargetPolicy p = policy(true, true, List.of(), List.of());
        assertThatThrownBy(() -> p.resolve("169.254.169.254")).isInstanceOf(TargetNotAllowedException.class).hasMessageContaining("link-local");
        assertThatThrownBy(() -> p.resolve("0.0.0.0")).isInstanceOf(TargetNotAllowedException.class).hasMessageContaining("wildcard");
        assertThatThrownBy(() -> p.resolve("::ffff:169.254.169.254")).isInstanceOf(TargetNotAllowedException.class);
    }

    @Test void privateNetworksAreConfigurable() throws Exception {
        assertThat(policy(false, true, List.of(), List.of()).resolve("10.1.2.3").getHostAddress()).isEqualTo("10.1.2.3");
        assertThatThrownBy(() -> policy(false, false, List.of(), List.of()).resolve("192.168.1.10")).isInstanceOf(TargetNotAllowedException.class);
    }

    @Test void allowAndDenyListsApply() throws Exception {
        NetworkTargetPolicy p = policy(false, true, List.of("10.20.0.0/16"), List.of("10.20.99.0/24"));
        assertThat(p.resolve("10.20.1.1")).isNotNull();
        assertThatThrownBy(() -> p.resolve("10.21.1.1")).isInstanceOf(TargetNotAllowedException.class).hasMessageContaining("allowed-cidrs");
        assertThatThrownBy(() -> p.resolve("10.20.99.7")).isInstanceOf(TargetNotAllowedException.class).hasMessageContaining("denied-cidrs");
    }

    @Test void loopbackCanBeEnabledForDevelopment() throws Exception {
        assertThat(policy(true, true, List.of(), List.of()).resolve("127.0.0.1").isLoopbackAddress()).isTrue();
    }
}
