package com.tomcatmanager.infrastructure;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface TomcatInstanceRepository extends JpaRepository<TomcatInstance, Long> {
    // open-in-view is disabled, so views need host and groups loaded up front.
    @EntityGraph(attributePaths = {"host", "groups"}) List<TomcatInstance> findAllByOrderByNameAsc();
    @EntityGraph(attributePaths = {"host", "groups"}) @Override Optional<TomcatInstance> findById(Long id);
    @EntityGraph(attributePaths = {"host", "groups"}) List<TomcatInstance> findByHostIdOrderByNameAsc(Long hostId);
    long countByEnabledTrue();
    boolean existsByHostId(Long hostId);
    boolean existsByHostIdAndNameIgnoreCase(Long hostId, String name);
}
