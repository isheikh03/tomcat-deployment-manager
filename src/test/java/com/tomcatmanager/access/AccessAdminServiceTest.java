package com.tomcatmanager.access;

import com.tomcatmanager.access.AccessGrant.Level;
import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.common.DisplayTime;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.users.AppUser;
import com.tomcatmanager.users.AppUserRepository;
import com.tomcatmanager.users.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Who may hand out which access. Teams: Blue (1) with host 10 / instance 100, Red (2) with host 20 / instance 200. */
class AccessAdminServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final UserTeamRepository userTeams = mock(UserTeamRepository.class);
    private final AccessGrantRepository grants = mock(AccessGrantRepository.class);
    private final ManagedHostRepository hosts = mock(ManagedHostRepository.class);
    private final TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final ServerGroupRepository groups = mock(ServerGroupRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessAdminService service = new AccessAdminService(teams, userTeams, grants, hosts, instances, applications, groups,
            users, audit, new DisplayTime("UTC"));

    /** Ann administers Blue only. */
    private final AccessScope ann = AccessScope.of("ann", Role.ADMIN, Set.of(1L), Map.of(100L, Level.DEPLOY), Set.of(10L), Set.of(1L), Set.of(1L));

    private static Team team(long id, String name, boolean isDefault) {
        Team t = new Team(name, null, "root");
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "defaultTeam", isDefault);
        return t;
    }

    private static AppUser account(long id, String name, Role role) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id); when(u.getUsername()).thenReturn(name); when(u.getRole()).thenReturn(role);
        return u;
    }

    @BeforeEach void data() {
        List<Team> all = List.of(team(1, "Blue", true), team(2, "Red", false));
        when(teams.findAll()).thenReturn(all);
        when(teams.findAllByOrderByNameAsc()).thenReturn(all);
        when(teams.findById(1L)).thenReturn(Optional.of(all.get(0)));
        when(teams.findById(2L)).thenReturn(Optional.of(all.get(1)));
        ManagedHost h10 = new ManagedHost(); h10.setId(10L); h10.setTeamId(1L); h10.setName("blue-web");
        ManagedHost h20 = new ManagedHost(); h20.setId(20L); h20.setTeamId(2L); h20.setName("red-web");
        when(hosts.findAll()).thenReturn(List.of(h10, h20));
        when(hosts.findAllByOrderByNameAsc()).thenReturn(List.of(h10, h20));
        TomcatInstance i100 = new TomcatInstance(); ReflectionTestUtils.setField(i100, "id", 100L); i100.setName("blue-1"); i100.setHost(h10);
        TomcatInstance i200 = new TomcatInstance(); ReflectionTestUtils.setField(i200, "id", 200L); i200.setName("red-1"); i200.setHost(h20);
        when(instances.findAllByOrderByNameAsc()).thenReturn(List.of(i100, i200));
    }

    @Test void anAdministratorAssignsOnlyTheirOwnTeams_andLeavesOtherTeamsAlone() {
        AppUser bob = account(5, "bob", Role.ADMIN);
        assertThatThrownBy(() -> service.setAdminTeams(bob, List.of(2L), ann)).isInstanceOf(NotPermittedException.class);

        UserTeam red = new UserTeam(5L, 2L, "root");
        when(userTeams.findByUserId(5L)).thenReturn(List.of(red));
        service.setAdminTeams(bob, List.of(1L), ann);
        ArgumentCaptor<UserTeam> saved = ArgumentCaptor.forClass(UserTeam.class);
        verify(userTeams).save(saved.capture());
        assertThat(saved.getValue().getTeamId()).isEqualTo(1L);
        verify(userTeams, never()).delete(red); // Ann does not administer Red
    }

    @Test void teamsGoToAdministratorsOnly() {
        assertThatThrownBy(() -> service.setAdminTeams(account(6, "op", Role.DEPLOYMENT_OPERATOR), List.of(1L), AccessScope.everything("root")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void grantsStayInsideTheActorsTeams() {
        AppUser op = account(6, "op", Role.DEPLOYMENT_OPERATOR);
        assertThatThrownBy(() -> service.grant(op, "HOST:20", Level.DEPLOY, null, null, ann)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> service.grant(op, "TEAM:2", Level.VIEW, null, null, ann)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> service.grant(op, "INSTANCE:999", Level.VIEW, null, null, ann)).isInstanceOf(java.util.NoSuchElementException.class);

        assertThat(service.grant(op, "HOST:10", Level.DEPLOY, LocalDate.now().plusDays(7), "release support", ann)).isTrue();
        ArgumentCaptor<AccessGrant> saved = ArgumentCaptor.forClass(AccessGrant.class);
        verify(grants).save(saved.capture());
        assertThat(saved.getValue().getScope()).isEqualTo(AccessGrant.Scope.HOST);
        assertThat(saved.getValue().getHostId()).isEqualTo(10L);
        assertThat(saved.getValue().getLevel()).isEqualTo(Level.DEPLOY);
        assertThat(saved.getValue().getExpiresAt()).isNotNull();
        verify(audit).success(any(), any(), any(), org.mockito.ArgumentMatchers.contains("all instances on host blue-web"));
    }

    @Test void aSecondGrantOnTheSameTargetUpdatesTheFirst() {
        AppUser op = account(6, "op", Role.DEPLOYMENT_OPERATOR);
        AccessGrant existing = AccessGrant.toInstance(6L, 100L, Level.VIEW, "ann");
        when(grants.findByUserId(6L)).thenReturn(List.of(existing));
        assertThat(service.grant(op, "INSTANCE:100", Level.DEPLOY, null, null, ann)).isFalse();
        assertThat(existing.getLevel()).isEqualTo(Level.DEPLOY);
        verify(grants).save(existing);
    }

    @Test void viewersGetViewOnly_andExpiryCannotBeInThePast() {
        AppUser vic = account(7, "vic", Role.VIEWER);
        assertThatThrownBy(() -> service.grant(vic, "TEAM:1", Level.DEPLOY, null, null, ann)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(vic, "TEAM:1", Level.VIEW, LocalDate.now().minusDays(1), null, ann)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(account(5, "bob", Role.ADMIN), "TEAM:1", Level.VIEW, null, null, ann)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(vic, "nonsense", Level.VIEW, null, null, ann)).isInstanceOf(IllegalArgumentException.class);
        verify(grants, never()).save(any());
    }

    @Test void onlyTheActorsTeamsGrantsCanBeRemoved() {
        AppUser op = account(6, "op", Role.DEPLOYMENT_OPERATOR);
        AccessGrant red = AccessGrant.toTeam(6L, 2L, Level.DEPLOY, "root");
        when(grants.findById(9L)).thenReturn(Optional.of(red));
        assertThatThrownBy(() -> service.revoke(op, 9L, ann)).isInstanceOf(NotPermittedException.class);
        assertThatThrownBy(() -> service.revoke(account(8, "other", Role.VIEWER), 9L, AccessScope.everything("root")))
                .as("a grant of another user").isInstanceOf(java.util.NoSuchElementException.class);
        service.revoke(op, 9L, AccessScope.everything("root"));
        verify(grants).delete(red);
    }

    @Test void onlyEmptyNonDefaultTeamsAreDeleted() {
        assertThatThrownBy(() -> service.deleteTeam(1L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("default");
        when(hosts.countByTeamId(2L)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteTeam(2L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("1 Linux host");
        when(hosts.countByTeamId(2L)).thenReturn(0L);
        service.deleteTeam(2L);
        verify(teams).delete(any(Team.class));
    }

    @Test void teamNamesAreUniqueIgnoringCase() {
        assertThat(service.teamNameProblem(null, "blue")).contains("already");
        assertThat(service.teamNameProblem(1L, "BLUE")).as("renaming a team to itself").isNull();
        assertThat(service.teamNameProblem(null, "x")).isNotNull();
        assertThat(service.teamNameProblem(null, "Green")).isNull();
    }

    @Test void aChangedRoleDropsAccessThatNoLongerApplies() {
        AppUser promoted = account(6, "op", Role.ADMIN);
        AccessGrant g = AccessGrant.toTeam(6L, 1L, Level.DEPLOY, "ann");
        when(grants.findByUserId(6L)).thenReturn(List.of(g));
        service.roleChanged(promoted, Role.DEPLOYMENT_OPERATOR);
        verify(grants).deleteAll(List.of(g));

        AppUser demoted = account(5, "bob", Role.VIEWER);
        UserTeam t = new UserTeam(5L, 1L, "root");
        when(userTeams.findByUserId(5L)).thenReturn(List.of(t));
        service.roleChanged(demoted, Role.ADMIN);
        verify(userTeams).deleteAll(List.of(t));
    }

    @Test void whoHasAccessListsTeamAdministratorsAndCoveringGrants() {
        AppUser bob = account(5, "bob", Role.ADMIN), op = account(6, "op", Role.DEPLOYMENT_OPERATOR), vic = account(7, "vic", Role.VIEWER);
        for (AppUser u : List.of(bob, op, vic)) { String name = u.getUsername(); when(u.isEnabled()).thenReturn(true); when(u.getLabel()).thenReturn(name); }
        when(users.findAll()).thenReturn(List.of(bob, op, vic));
        when(userTeams.findByTeamId(1L)).thenReturn(List.of(new UserTeam(5L, 1L, "root")));
        when(grants.findAll()).thenReturn(List.of(AccessGrant.toHost(6L, 10L, Level.DEPLOY, "ann"),
                AccessGrant.toTeam(7L, 1L, Level.DEPLOY, "ann"), AccessGrant.toInstance(6L, 200L, Level.DEPLOY, "root")));
        TomcatInstance blue = instances.findAllByOrderByNameAsc().get(0);
        var holders = service.holdersOfInstance(blue);
        assertThat(holders).extracting(AccessAdminService.Holder::username).containsExactly("bob", "op", "vic");
        assertThat(holders.get(2).level()).as("a viewer's grant is View").isEqualTo(Level.VIEW);
    }
}
