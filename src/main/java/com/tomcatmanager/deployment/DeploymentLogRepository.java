package com.tomcatmanager.deployment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, Long> {
    List<DeploymentLog> findByRunIdAndIdGreaterThanOrderByIdAsc(Long runId, Long afterId);
}
