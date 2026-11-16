package com.tomcatmanager.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, Long> {
    List<AccessGrant> findByUserId(Long userId);
    List<AccessGrant> findByTeamId(Long teamId);
    List<AccessGrant> findByHostId(Long hostId);
    List<AccessGrant> findByInstanceId(Long instanceId);
}
