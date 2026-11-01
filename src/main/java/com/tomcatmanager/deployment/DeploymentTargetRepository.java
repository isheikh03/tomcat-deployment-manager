package com.tomcatmanager.deployment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentTargetRepository extends JpaRepository<DeploymentTarget, Long> {
    @EntityGraph(attributePaths = "run") List<DeploymentTarget> findByRunIdOrderBySequenceAsc(Long runId);
    List<DeploymentTarget> findByStateIn(Collection<TargetState> states);
    @EntityGraph(attributePaths = "run") List<DeploymentTarget> findByInstanceIdAndStateIn(Long instanceId, Collection<TargetState> states);

    /** A target with its run, application and release loaded (open-in-view is off). */
    @Query("""
            select t from DeploymentTarget t join fetch t.run r join fetch r.application left join fetch r.release rel left join fetch rel.artifact
            where t.id = :id""")
    Optional<DeploymentTarget> findWithRun(Long id);

    /**
     * What was last verified as deployed on an instance at a context path (SUCCEEDED or reconciled to
     * SUCCEEDED). The run's release is null when a rollback restored a server backup not in the library.
     */
    @Query("""
            select t from DeploymentTarget t join fetch t.run r left join fetch r.release rel left join fetch rel.artifact join fetch r.application
            where t.instanceId = :instanceId and t.contextPath = :contextPath and t.state = com.tomcatmanager.deployment.TargetState.SUCCEEDED
            order by t.finishedAt desc limit 1""")
    Optional<DeploymentTarget> findCurrentDeployment(Long instanceId, String contextPath);

    /** Every context currently known to be deployed on an instance (latest success per context). */
    @Query("""
            select t from DeploymentTarget t join fetch t.run r left join fetch r.release rel join fetch r.application
            where t.instanceId = :instanceId and t.state = com.tomcatmanager.deployment.TargetState.SUCCEEDED
              and t.finishedAt = (select max(t2.finishedAt) from DeploymentTarget t2
                                  where t2.instanceId = t.instanceId and t2.contextPath = t.contextPath
                                    and t2.state = com.tomcatmanager.deployment.TargetState.SUCCEEDED)
            order by t.contextPath""")
    List<DeploymentTarget> findCurrentDeployments(Long instanceId);

    /** Later targets on the same instance and context that may have changed the deployed application. */
    @Query("""
            select t from DeploymentTarget t join fetch t.run
            where t.instanceId = :instanceId and t.contextPath = :contextPath and t.id > :afterId
              and t.state not in (com.tomcatmanager.deployment.TargetState.PREFLIGHT_FAILED, com.tomcatmanager.deployment.TargetState.SKIPPED)
            order by t.id""")
    List<DeploymentTarget> findLaterTargets(Long instanceId, String contextPath, Long afterId);

    long countByState(TargetState state);
}
