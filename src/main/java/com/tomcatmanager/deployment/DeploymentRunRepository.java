package com.tomcatmanager.deployment;

import com.tomcatmanager.infrastructure.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRunRepository extends JpaRepository<DeploymentRun, Long> {
    @EntityGraph(attributePaths = {"application", "release", "release.artifact"}) @Override Optional<DeploymentRun> findById(Long id);
    Optional<DeploymentRun> findByRequestId(UUID requestId);
    List<DeploymentRun> findByStateIn(Collection<DeploymentState> states);
    long countByStateIn(Collection<DeploymentState> states);
    boolean existsByReleaseId(Long releaseId);

    /** History search. Pass {@code appId = 0} for all applications and null environment/state for no filter. */
    @EntityGraph(attributePaths = {"application", "release"})
    @Query("""
            select r from DeploymentRun r
            where (:appId = 0 or r.application.id = :appId)
              and (:all = true or r.application.teamId in :managedTeams
                   or exists (select t.id from DeploymentTarget t where t.run = r and t.instanceId in :instanceIds))
              and (:environment is null or r.environment = :environment)
              and (:state is null or r.state = :state)
            order by r.requestedAt desc""")
    Page<DeploymentRun> search(long appId, Environment environment, DeploymentState state, boolean all, Collection<Long> managedTeams,
                               Collection<Long> instanceIds, Pageable pageable);

    @EntityGraph(attributePaths = {"application", "release"})
    List<DeploymentRun> findTop6ByOrderByRequestedAtDesc();

    @EntityGraph(attributePaths = {"application", "release"})
    List<DeploymentRun> findByReleaseIdOrderByRequestedAtDesc(Long releaseId);

    /** Rollback runs of the given original targets, newest first. */
    @EntityGraph(attributePaths = {"application", "release"})
    List<DeploymentRun> findByRollbackOfTargetIdInOrderByRequestedAtDesc(Collection<Long> targetIds);

    @EntityGraph(attributePaths = {"application", "release"})
    List<DeploymentRun> findTop5ByKindOrderByRequestedAtDesc(DeploymentRun.Kind kind);

    /** Ids of the runs of the given teams' applications (for the audit log of an administrator). */
    @Query("select r.id from DeploymentRun r where r.application.teamId in :teams")
    List<Long> idsOfTeams(Collection<Long> teams);
}
