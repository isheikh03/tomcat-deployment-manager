package com.tomcatmanager.infrastructure;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface ManagedHostRepository extends JpaRepository<ManagedHost, Long> {
    boolean existsByNameIgnoreCase(String name);
    Optional<ManagedHost> findByNameIgnoreCase(String name);
    List<ManagedHost> findAllByOrderByNameAsc();
    long countByEnabledTrue();
    long countByTeamId(Long teamId);
}
