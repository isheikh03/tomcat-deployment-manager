package com.tomcatmanager.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<ManagedApplication, Long> {
    Optional<ManagedApplication> findByNameIgnoreCase(String name);
    List<ManagedApplication> findAllByOrderByNameAsc();
    long countByTeamId(Long teamId);

    @Query("select a from ManagedApplication a where :q = '' or lower(a.name) like :q or lower(coalesce(a.description, '')) like :q order by a.name")
    List<ManagedApplication> search(String q);
}
