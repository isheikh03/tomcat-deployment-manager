package com.tomcatmanager.connectivity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ConnectionTestResultRepository extends JpaRepository<ConnectionTestResult, Long> {
    Page<ConnectionTestResult> findByHostIdOrderByFinishedAtDesc(Long hostId, Pageable pageable);
    Page<ConnectionTestResult> findByInstanceIdOrderByFinishedAtDesc(Long instanceId, Pageable pageable);
    Optional<ConnectionTestResult> findFirstByHostIdAndPresentedHostKeyIsNotNullOrderByFinishedAtDesc(Long hostId);

    /** Latest host-level SSH test per host. */
    @Query("""
            select r from ConnectionTestResult r
            where r.testType = com.tomcatmanager.connectivity.ConnectionTestResult.TestType.HOST_SSH
              and r.finishedAt = (select max(r2.finishedAt) from ConnectionTestResult r2
                                  where r2.hostId = r.hostId and r2.testType = r.testType)""")
    List<ConnectionTestResult> findLatestHostTests();

    /** Latest instance-level test per instance (whatever its deployment method). */
    @Query("""
            select r from ConnectionTestResult r
            where r.instanceId is not null
              and r.finishedAt = (select max(r2.finishedAt) from ConnectionTestResult r2 where r2.instanceId = r.instanceId)""")
    List<ConnectionTestResult> findLatestInstanceTests();
}
