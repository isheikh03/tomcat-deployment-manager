package com.tomcatmanager.health;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface HealthCheckResultRepository extends JpaRepository<HealthCheckResult, Long> {
    List<HealthCheckResult> findByInstanceIdOrderByCheckedAtDescIdDesc(Long instanceId, Pageable pageable);

    @Modifying @Transactional
    @Query("delete from HealthCheckResult r where r.checkedAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
