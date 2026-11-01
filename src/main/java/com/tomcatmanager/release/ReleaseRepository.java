package com.tomcatmanager.release;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<Release, Long> {
    @EntityGraph(attributePaths = {"application", "artifact"}) @Override Optional<Release> findById(Long id);

    /** Library search. Pass {@code appId = 0} for all applications and {@code q = ""} for no text filter. */
    @EntityGraph(attributePaths = {"application", "artifact"})
    @Query(value = """
            select r from Release r
            where (:appId = 0 or r.application.id = :appId)
              and (:includeArchived = true or r.archived = false)
              and (:q = '' or lower(r.versionLabel) like :q or lower(r.originalFilename) like :q
                   or lower(r.application.name) like :q or r.artifact.sha256 like :q or lower(coalesce(r.releaseNotes, '')) like :q)""",
           countQuery = """
            select count(r) from Release r
            where (:appId = 0 or r.application.id = :appId)
              and (:includeArchived = true or r.archived = false)
              and (:q = '' or lower(r.versionLabel) like :q or lower(r.originalFilename) like :q
                   or lower(r.application.name) like :q or r.artifact.sha256 like :q or lower(coalesce(r.releaseNotes, '')) like :q)""")
    Page<Release> search(long appId, boolean includeArchived, String q, Pageable pageable);

    @EntityGraph(attributePaths = {"application", "artifact"})
    List<Release> findTop5ByOrderByUploadedAtDesc();

    @EntityGraph(attributePaths = {"application", "artifact"})
    List<Release> findByApplicationIdOrderByUploadedAtDesc(Long applicationId);

    @EntityGraph(attributePaths = {"application", "artifact"})
    List<Release> findByArtifactIdOrderByUploadedAtDesc(Long artifactId);

    Optional<Release> findByApplicationIdAndArtifactId(Long applicationId, Long artifactId);
    boolean existsByApplicationIdAndVersionLabelIgnoreCase(Long applicationId, String versionLabel);
    long countByApplicationId(Long applicationId);
    long countByArtifactId(Long artifactId);
    long countByArchivedFalse();

    /** Deployments that used this release (deployment history must keep referring to it). */
    @Query(value = "select count(*) from deployment_runs where release_id = :releaseId", nativeQuery = true)
    long countDeployments(long releaseId);

    /** Release count and latest upload per application, as [applicationId, count, latestUploadedAt]. */
    @Query("select r.application.id, count(r), max(r.uploadedAt) from Release r group by r.application.id")
    List<Object[]> statisticsByApplication();
}
