package com.tomcatmanager.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InstanceHealthStatusRepository extends JpaRepository<InstanceHealthStatus, Long> {
    List<InstanceHealthStatus> findByStatusIn(Collection<InstanceHealthStatus.Status> statuses);
    List<InstanceHealthStatus> findTop10ByLastRecoveredAtNotNullOrderByLastRecoveredAtDesc();
}
