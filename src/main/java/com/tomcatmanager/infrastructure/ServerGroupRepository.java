package com.tomcatmanager.infrastructure;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ServerGroupRepository extends JpaRepository<ServerGroup, Long> {
    boolean existsByNameIgnoreCase(String name);
    long countByTeamId(Long teamId);
}
