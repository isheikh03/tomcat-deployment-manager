package com.tomcatmanager.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, NotificationRead.Key> {
    long countByUsernameAndNotificationIdGreaterThan(String username, long notificationId);

    @Query("select r.notificationId from NotificationRead r where r.username = :username and r.notificationId in :ids")
    List<Long> findReadIds(String username, Collection<Long> ids);

    /** Rows now covered by the user's "read up to" marker. */
    @Modifying
    @Query("delete from NotificationRead r where r.username = :username and r.notificationId <= :upTo")
    int deleteCovered(String username, long upTo);
}
