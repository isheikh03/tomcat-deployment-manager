package com.tomcatmanager.release;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface StoredArtifactRepository extends JpaRepository<StoredArtifact, Long> {
    Optional<StoredArtifact> findBySha256(String sha256);

    @Query("select coalesce(sum(a.sizeBytes), 0) from StoredArtifact a")
    long totalStoredBytes();
}
