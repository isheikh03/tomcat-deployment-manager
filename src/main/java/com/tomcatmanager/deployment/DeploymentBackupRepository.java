package com.tomcatmanager.deployment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentBackupRepository extends JpaRepository<DeploymentBackup, Long> {
    Optional<DeploymentBackup> findByTargetId(Long targetId);
    List<DeploymentBackup> findByTargetIdIn(Collection<Long> targetIds);
    List<DeploymentBackup> findByDeletedAtIsNullOrderByCreatedAtDesc();
    List<DeploymentBackup> findAllByOrderByCreatedAtDesc();

    /** Bytes of server backup files currently kept (deleted backups excluded). */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(b.sizeBytes), 0) from DeploymentBackup b
            where b.deletedAt is null and b.source = com.tomcatmanager.deployment.DeploymentBackup.Source.REMOTE_WAR""")
    long totalServerBackupBytes();

    @org.springframework.data.jpa.repository.Query("""
            select count(b) from DeploymentBackup b
            where b.deletedAt is null and b.source = com.tomcatmanager.deployment.DeploymentBackup.Source.REMOTE_WAR""")
    long countServerBackups();
}
