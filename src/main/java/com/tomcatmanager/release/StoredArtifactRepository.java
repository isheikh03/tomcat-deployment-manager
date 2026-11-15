package com.tomcatmanager.release;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface StoredArtifactRepository extends JpaRepository<StoredArtifact, Long> {
    Optional<StoredArtifact> findBySha256(String sha256);

    /** Bytes of WAR files currently on disk (files removed by storage cleanup excluded). */
    @Query("select coalesce(sum(a.sizeBytes), 0) from StoredArtifact a where a.fileRemovedAt is null")
    long totalStoredBytes();

    long countByFileRemovedAtIsNull();
    long countByFileRemovedAtIsNotNull();
    List<StoredArtifact> findByFileRemovedAtIsNull();
}
