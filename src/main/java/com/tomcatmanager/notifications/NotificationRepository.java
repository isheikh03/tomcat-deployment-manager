package com.tomcatmanager.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    long countByIdGreaterThan(long id);
    List<Notification> findTop8ByOrderByIdDesc();
    Page<Notification> findAllByOrderByIdDesc(Pageable pageable);
    Page<Notification> findByCategoryOrderByIdDesc(Notification.Category category, Pageable pageable);

    @Query("select coalesce(max(n.id), 0) from Notification n")
    long maxId();
}
