package com.tomcatmanager.infrastructure;

import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.connectivity.ConnectivityStatus.State;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureListingTest {

    static ManagedHost host(long id, String name, String hostname, boolean enabled, boolean pinned) {
        ManagedHost h = new ManagedHost();
        h.setId(id); h.setName(name); h.setHostname(hostname); h.setSshUsername("deploy"); h.setEnabled(enabled);
        if (pinned) h.setSshHostKeyFingerprint("SHA256:abc");
        return h;
    }

    static TomcatInstance instance(long id, String name, ManagedHost host, Environment env, TomcatInstance.DeploymentMethod method, ServerGroup... groups) {
        TomcatInstance i = new TomcatInstance();
        ReflectionTestUtils.setField(i, "id", id);
        i.setName(name); i.setHost(host); i.setEnvironment(env); i.setDeploymentMethod(method); i.setEnabled(true);
        i.setGroups(Set.of(groups));
        return i;
    }

    static ServerGroup group(long id, String name) {
        ServerGroup g = new ServerGroup();
        g.setId(id); g.setName(name);
        return g;
    }

    private static ConnectivityStatus status(State s) { return new ConnectivityStatus(s, 1L, Instant.now()); }

    private final ManagedHost web = host(1, "Web Server", "10.0.0.5", true, true);
    private final ManagedHost db = host(2, "database-live", "db.example.com", false, false);
    private final ManagedHost app = host(3, "App Server", "10.0.0.9", true, false);
    private final List<ManagedHost> hosts = List.of(web, db, app);
    private final Map<Long, ConnectivityStatus> hostStatuses = Map.of(1L, status(State.VERIFIED), 2L, status(State.FAILED));

    private List<String> names(List<ManagedHost> list) { return list.stream().map(ManagedHost::getName).toList(); }

    @Test void hostSearchMatchesEveryWordInAnyField() {
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter("server 10.0.0", null, null, null, null), hostStatuses)))
                .containsExactly("App Server", "Web Server");
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter("EXAMPLE", null, null, null, null), hostStatuses)))
                .containsExactly("database-live");
    }

    @Test void hostFiltersCombine() {
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter(null, State.NEVER_TESTED, null, null, null), hostStatuses)))
                .as("no result recorded counts as never tested").containsExactly("App Server");
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter(null, null, true, false, null), hostStatuses)))
                .containsExactly("App Server");
    }

    @Test void problemsFirstPutsFailedBeforeNeverTestedBeforeVerified() {
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter(null, null, null, null, "problems"), hostStatuses)))
                .containsExactly("database-live", "App Server", "Web Server");
        assertThat(names(InfrastructureListing.hosts(hosts, new InfrastructureListing.HostFilter(null, null, null, null, "name-desc"), hostStatuses)))
                .containsExactly("Web Server", "database-live", "App Server");
    }

    @Test void instanceFiltersByEnvironmentHostMethodAndGroup() {
        ServerGroup blue = group(7, "blue");
        TomcatInstance a = instance(10, "sol-prod-1", web, Environment.PRODUCTION, TomcatInstance.DeploymentMethod.SSH_SFTP, blue);
        TomcatInstance b = instance(11, "sol-test", app, Environment.TESTING, TomcatInstance.DeploymentMethod.TOMCAT_MANAGER);
        TomcatInstance c = instance(12, "scratch", app, null, TomcatInstance.DeploymentMethod.SSH_SFTP);
        List<TomcatInstance> all = List.of(a, b, c);
        Map<Long, ConnectivityStatus> none = Map.of();
        java.util.function.Function<InfrastructureListing.InstanceFilter, List<String>> run =
                f -> InfrastructureListing.instances(all, f, none).stream().map(TomcatInstance::getName).toList();

        assertThat(run.apply(new InfrastructureListing.InstanceFilter(null, "NONE", null, null, null, null, null, null))).containsExactly("scratch");
        assertThat(run.apply(new InfrastructureListing.InstanceFilter(null, "PRODUCTION", null, null, null, null, null, null))).containsExactly("sol-prod-1");
        assertThat(run.apply(new InfrastructureListing.InstanceFilter(null, null, 3L, null, null, null, null, null))).containsExactly("scratch", "sol-test");
        assertThat(run.apply(new InfrastructureListing.InstanceFilter(null, null, null, TomcatInstance.DeploymentMethod.SSH_SFTP, 7L, null, null, null))).containsExactly("sol-prod-1");
        assertThat(run.apply(new InfrastructureListing.InstanceFilter("app sol", null, null, null, null, null, null, null)))
                .as("words can match the instance and its host").containsExactly("sol-test");
        assertThat(run.apply(new InfrastructureListing.InstanceFilter(null, null, null, null, null, null, null, "environment")))
                .containsExactly("sol-test", "sol-prod-1", "scratch");
    }

    @Test void unknownValuesMeanNoFilter() {
        assertThat(InfrastructureListing.parseEnum(State.class, "nonsense")).isNull();
        assertThat(InfrastructureListing.parseId("12x")).isNull();
        assertThat(InfrastructureListing.parseFlag("maybe", "enabled", "disabled")).isNull();
        assertThat(InfrastructureListing.parseFlag("disabled", "enabled", "disabled")).isFalse();
    }
}
