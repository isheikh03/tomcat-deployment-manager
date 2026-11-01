package com.tomcatmanager.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/** Read and insert only: the table is append-only (enforced by a database trigger). */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Search. Empty strings and null bounds mean "no filter"; {@code actor} and {@code text} match case-insensitively. */
    @Query("""
            select e from AuditEvent e
            where (:actor = '' or lower(e.actor) like lower(concat('%', :actor, '%')))
              and (:action = '' or e.action = :action)
              and (:resourceType = '' or e.resourceType = :resourceType)
              and (:outcome is null or e.outcome = :outcome)
              and (:text = '' or lower(e.summary) like lower(concat('%', :text, '%')) or e.resourceId = :text or e.correlationId = :text)
              and e.occurredAt >= :from and e.occurredAt < :to
            order by e.occurredAt desc, e.id desc""")
    Page<AuditEvent> search(String actor, String action, String resourceType, AuditEvent.Outcome outcome, String text,
                            Instant from, Instant to, Pageable pageable);

    @Query("select distinct e.action from AuditEvent e order by e.action")
    List<String> distinctActions();

    @Query("select distinct e.resourceType from AuditEvent e where e.resourceType is not null order by e.resourceType")
    List<String> distinctResourceTypes();

    List<AuditEvent> findTop10ByActionInOrderByOccurredAtDesc(List<String> actions);
}
