package com.tomcatmanager.access;

import com.tomcatmanager.access.AccessGrant.Level;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.users.AppUser;
import com.tomcatmanager.users.AppUserRepository;
import com.tomcatmanager.users.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who sees and deploys what. Teams: A (id 1) with host 10 (instances 100, 101) and B (id 2) with hosts 20 (instance 200)
 * and 21 (instance 210).
 */
class AccessTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserTeamRepository userTeams = mock(UserTeamRepository.class);
    private final AccessGrantRepository grants = mock(AccessGrantRepository.class);
    private final ManagedHostRepository hosts = mock(ManagedHostRepository.class);
    private final TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
    private final Access access = new Access(users, userTeams, grants, hosts, instances);
    private final List<TomcatInstance> allInstances = new ArrayList<>();

    private static ManagedHost host(long id, long team) {
        ManagedHost h = new ManagedHost();
        h.setId(id); h.setTeamId(team); h.setName("host-" + id);
        return h;
    }

    private TomcatInstance instance(long id, ManagedHost host) {
        TomcatInstance i = new TomcatInstance();
        ReflectionTestUtils.setField(i, "id", id);
        i.setName("tomcat-" + id); i.setHost(host);
        allInstances.add(i);
        return i;
    }

    private AppUser user(long id, String name, Role role) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id); when(u.getUsername()).thenReturn(name); when(u.getRole()).thenReturn(role); when(u.isEnabled()).thenReturn(true);
        when(users.findByUsernameIgnoreCase(name)).thenReturn(Optional.of(u));
        return u;
    }

    @BeforeEach void infrastructure() {
        ManagedHost h10 = host(10, 1), h20 = host(20, 2), h21 = host(21, 2);
        when(hosts.findAll()).thenReturn(List.of(h10, h20, h21));
        instance(100, h10); instance(101, h10); instance(200, h20); instance(210, h21);
        when(instances.findAllByOrderByNameAsc()).thenReturn(allInstances);
    }

    @Test void aSuperUserSeesAndDeploysEverywhere() {
        user(1, "root", Role.SUPER_ADMIN);
        AccessScope s = access.forUser("root");
        assertThat(s.isEverything()).isTrue();
        assertThat(s.canDeployTo(210L)).isTrue();
        assertThat(s.canManageTeam(2L)).isTrue();
    }

    @Test void anAdministratorWorksOnlyInTheirTeams() {
        user(2, "ann", Role.ADMIN);
        when(userTeams.findByUserId(2L)).thenReturn(List.of(new UserTeam(2L, 1L, "root")));
        AccessScope s = access.forUser("ann");
        assertThat(s.canManageTeam(1L)).isTrue();
        assertThat(s.canManageTeam(2L)).isFalse();
        assertThat(s.canDeployTo(100L)).isTrue();
        assertThat(s.canViewInstance(200L)).isFalse();
        assertThat(s.canViewHost(20L, 2L)).isFalse();
        assertThat(s.canUploadTo(1L)).isTrue();
        assertThat(s.canUploadTo(2L)).isFalse();
    }

    @Test void aHostGrantCoversEveryInstanceOfTheHostButNothingElse() {
        user(3, "op", Role.DEPLOYMENT_OPERATOR);
        when(grants.findByUserId(3L)).thenReturn(List.of(AccessGrant.toHost(3L, 20L, Level.DEPLOY, "ann")));
        AccessScope s = access.forUser("op");
        assertThat(s.canDeployTo(200L)).isTrue();
        assertThat(s.canViewInstance(210L)).as("another host of the same team").isFalse();
        assertThat(s.canViewHost(20L, 2L)).isTrue();
        assertThat(s.canManageTeam(2L)).as("operators never administer").isFalse();
        assertThat(s.canUploadTo(2L)).isTrue();

        // An instance added to the host later is covered too.
        instance(202, host(20, 2));
        assertThat(access.forUser("op").canDeployTo(202L)).isTrue();
    }

    @Test void aTeamGrantCoversAllHostsOfTheTeam() {
        user(3, "op", Role.DEPLOYMENT_OPERATOR);
        when(grants.findByUserId(3L)).thenReturn(List.of(AccessGrant.toTeam(3L, 2L, Level.DEPLOY, "ann")));
        AccessScope s = access.forUser("op");
        assertThat(s.canDeployTo(200L)).isTrue();
        assertThat(s.canDeployTo(210L)).isTrue();
        assertThat(s.canViewInstance(100L)).isFalse();
    }

    @Test void aViewersGrantsAreAlwaysViewOnly() {
        user(4, "vic", Role.VIEWER);
        when(grants.findByUserId(4L)).thenReturn(List.of(AccessGrant.toTeam(4L, 1L, Level.DEPLOY, "ann")));
        AccessScope s = access.forUser("vic");
        assertThat(s.canViewInstance(100L)).isTrue();
        assertThat(s.canDeployTo(100L)).isFalse();
        assertThat(s.levelOn(100L)).isEqualTo(Level.VIEW);
        assertThat(s.canUploadTo(1L)).isFalse();
    }

    @Test void theHigherOfOverlappingGrantsApplies_andExpiredGrantsDoNot() {
        user(3, "op", Role.DEPLOYMENT_OPERATOR);
        AccessGrant view = AccessGrant.toTeam(3L, 1L, Level.VIEW, "ann");
        AccessGrant deployOne = AccessGrant.toInstance(3L, 101L, Level.DEPLOY, "ann");
        AccessGrant expired = AccessGrant.toHost(3L, 20L, Level.DEPLOY, "ann");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(grants.findByUserId(3L)).thenReturn(List.of(view, deployOne, expired));
        AccessScope s = access.forUser("op");
        assertThat(s.levelOn(100L)).isEqualTo(Level.VIEW);
        assertThat(s.levelOn(101L)).isEqualTo(Level.DEPLOY);
        assertThat(s.canViewInstance(200L)).as("expired grant").isFalse();
    }

    @Test void aDisabledOrUnknownAccountHasNoAccess() {
        AppUser u = user(5, "gone", Role.ADMIN);
        when(u.isEnabled()).thenReturn(false);
        assertThat(access.forUser("gone").canViewInstance(100L)).isFalse();
        assertThat(access.forUser("nobody").isEverything()).isFalse();
        assertThat(access.forUser("nobody").canViewTeam(1L)).isFalse();
    }

    @Test void teamsByUserLists_adminTeamsAndTheTeamsGrantsPointInto() {
        when(userTeams.findAll()).thenReturn(List.of(new UserTeam(2L, 1L, "root")));
        when(grants.findAll()).thenReturn(List.of(AccessGrant.toHost(3L, 20L, Level.DEPLOY, "ann"), AccessGrant.toInstance(4L, 100L, Level.VIEW, "ann")));
        var teams = access.teamsByUser();
        assertThat(teams.get(2L)).containsExactly(1L);
        assertThat(teams.get(3L)).containsExactly(2L);
        assertThat(teams.get(4L)).containsExactly(1L);
    }
}
