package com.tomcatmanager.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
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
              and (:all = true or e.actor in :actors
                   or (e.resourceType = 'USER' and e.resourceId in :actors)
                   or (e.resourceType = 'HOST' and e.resourceId in :hosts)
                   or (e.resourceType = 'INSTANCE' and e.resourceId in :instances)
                   or (e.resourceType = 'GROUP' and e.resourceId in :groups)
                   or (e.resourceType = 'RELEASE' and e.resourceId in :releases)
                   or (e.resourceType in ('DEPLOYMENT', 'ROLLBACK') and e.resourceId in :runs))
            order by e.occurredAt desc, e.id desc""")
    Page<AuditEvent> search(String actor, String action, String resourceType, AuditEvent.Outcome outcome, String text,
                            Instant from, Instant to, boolean all, Collection<String> actors, Collection<String> hosts,
                            Collection<String> instances, Collection<String> groups, Collection<String> releases,
                            Collection<String> runs, Pageable pageable);

    @Query("select distinct e.action from AuditEvent e order by e.action")
    List<String> distinctActions();

    @Query("select distinct e.resourceType from AuditEvent e where e.resourceType is not null order by e.resourceType")
    List<String> distinctResourceTypes();

    List<AuditEvent> findTop10ByActionInOrderByOccurredAtDesc(List<String> actions);
}
