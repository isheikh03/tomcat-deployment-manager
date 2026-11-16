package com.tomcatmanager.notifications;

import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-application notifications for important operational events, optionally also emailed through
 * the externally configured SMTP server. Publishing never fails the caller: a notification or email
 * failure is logged and recorded, but never changes deployment or monitoring results.
 */
@Service
public class NotificationService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final NotificationReadMarkerRepository markers;
    private final NotificationReadRepository reads;
    private final SettingsService settings;
    private final ObjectProvider<JavaMailSender> mail;
    private final TransactionTemplate tx;
    private final String from;
    private final String baseUrl;
    private final ExecutorService emailWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-email"); t.setDaemon(true); return t;
    });

    public NotificationService(NotificationRepository notifications, NotificationReadMarkerRepository markers, NotificationReadRepository reads,
                               SettingsService settings, ObjectProvider<JavaMailSender> mail, TransactionTemplate tx,
                               @Value("${app.notifications.email-from:tomcat-deployment-manager@localhost}") String from,
                               @Value("${app.notifications.base-url:}") String baseUrl, @Value("${spring.mail.host:}") String mailHost) {
        this.notifications = notifications; this.markers = markers; this.reads = reads; this.settings = settings; this.tx = tx;
        // A blank host still makes Spring create a mail sender; treat it as "no SMTP configured".
        this.mail = mailHost == null || mailHost.isBlank() ? new ObjectProvider<>() {
            @Override public JavaMailSender getObject() { throw new IllegalStateException("No SMTP server configured."); }
            @Override public JavaMailSender getIfAvailable() { return null; }
        } : mail;
        this.from = from; this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public void publish(Notification.Level level, Notification.Category category, String title, String message, String link) {
        try {
            Notification saved = notifications.save(new Notification(level, category, DiagnosticSanitizer.clean(title, 200),
                    DiagnosticSanitizer.clean(message, 1000), link));
            if (settings.emailEnabled() && level.atLeast(Notification.Level.valueOf(settings.emailMinLevel())) && !settings.emailRecipients().isEmpty())
                emailWorker.execute(() -> email(saved.getId()));
        } catch (RuntimeException e) {
            log.error("Could not record notification '{}': {}", title, e.toString());
        }
    }

    private void email(long id) {
        Notification n = notifications.findById(id).orElse(null);
        if (n == null) return;
        JavaMailSender sender = mail.getIfAvailable();
        Notification.EmailStatus status;
        String error = null;
        if (sender == null) {
            status = Notification.EmailStatus.FAILED;
            error = "No SMTP server is configured (spring.mail.host).";
        } else {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(settings.emailRecipients().toArray(String[]::new));
                message.setSubject("[Tomcat Deployment Manager] " + n.getTitle());
                message.setText(n.getMessage() + (n.getLink() != null && !baseUrl.isEmpty() ? "\n\n" + baseUrl + n.getLink() : "")
                        + "\n\n" + n.getLevel() + " · " + n.getCategory() + " · " + n.getCreatedAt());
                sender.send(message);
                status = Notification.EmailStatus.SENT;
            } catch (RuntimeException e) {
                status = Notification.EmailStatus.FAILED;
                error = DiagnosticSanitizer.clean(e.getClass().getSimpleName() + ": " + e.getMessage(), 300);
                log.warn("Notification email {} could not be sent: {}", id, error);
            }
        }
        Notification.EmailStatus finalStatus = status; String finalError = error;
        tx.executeWithoutResult(s -> notifications.findById(id).ifPresent(x -> { x.emailResult(finalStatus, finalError); notifications.save(x); }));
    }

    /** Sends a test email synchronously; returns null on success or the error. */
    public String sendTestEmail(String actor) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) return "No SMTP server is configured (set SPRING_MAIL_HOST and related settings).";
        List<String> to = settings.emailRecipients();
        if (to.isEmpty()) return "Add at least one recipient first.";
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from); message.setTo(to.toArray(String[]::new));
            message.setSubject("[Tomcat Deployment Manager] Test email");
            message.setText("This is a test email requested by " + actor + ". Notification emails are configured correctly.");
            sender.send(message);
            return null;
        } catch (RuntimeException e) {
            return DiagnosticSanitizer.clean(e.getClass().getSimpleName() + ": " + e.getMessage(), 300);
        }
    }

    public long unreadCount(String username) {
        long upTo = lastRead(username);
        return Math.max(0, notifications.countByIdGreaterThan(upTo) - reads.countByUsernameAndNotificationIdGreaterThan(username, upTo));
    }

    /** Of {@code ids}, those the user has read: covered by their marker or marked read one by one. */
    public Set<Long> readIds(String username, Collection<Long> ids) {
        long upTo = lastRead(username);
        Set<Long> read = new HashSet<>();
        for (Long id : ids) if (id <= upTo) read.add(id);
        List<Long> above = ids.stream().filter(id -> id > upTo).toList();
        if (!above.isEmpty()) read.addAll(reads.findReadIds(username, above));
        return read;
    }

    public void markRead(String username, long notificationId) {
        if (!notifications.existsById(notificationId)) throw new NoSuchElementException("Notification " + notificationId + " not found.");
        if (notificationId <= lastRead(username)) return;
        try {
            tx.executeWithoutResult(s -> {
                if (!reads.existsById(new NotificationRead.Key(username, notificationId))) reads.save(new NotificationRead(username, notificationId));
            });
        } catch (DataIntegrityViolationException e) {
            // marked read a moment ago from another tab
        }
    }

    public long lastRead(String username) { return markers.findById(username).map(NotificationReadMarker::getLastReadId).orElse(0L); }

    public List<Notification> recent() { return notifications.findTop8ByOrderByIdDesc(); }

    public Page<Notification> page(Notification.Category category, int page) {
        PageRequest request = PageRequest.of(Math.max(0, page), 25);
        return category == null ? notifications.findAllByOrderByIdDesc(request) : notifications.findByCategoryOrderByIdDesc(category, request);
    }

    // ---- Limited to the notifications a user may see (about their teams' hosts, instances and deployments) ----

    public long unreadCount(String username, java.util.function.Predicate<Notification> visible) {
        long upTo = lastRead(username);
        List<Long> candidates = notifications.findByIdGreaterThanOrderByIdDesc(upTo).stream().filter(visible).map(Notification::getId).toList();
        if (candidates.isEmpty()) return 0;
        return candidates.size() - readIds(username, candidates).size();
    }

    public List<Notification> recent(java.util.function.Predicate<Notification> visible) {
        return notifications.findTop200ByOrderByIdDesc().stream().filter(visible).limit(8).toList();
    }

    public Page<Notification> page(Notification.Category category, int page, java.util.function.Predicate<Notification> visible) {
        List<Notification> all = (category == null ? notifications.findAllByOrderByIdDesc() : notifications.findByCategoryOrderByIdDesc(category))
                .stream().filter(visible).toList();
        PageRequest request = PageRequest.of(Math.max(0, page), 25);
        int from = (int) Math.min(request.getOffset(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(from, Math.min(from + 25, all.size())), request, all.size());
    }

    public void markAllRead(String username) {
        long max = notifications.maxId();
        tx.executeWithoutResult(s -> {
            NotificationReadMarker marker = markers.findById(username).orElseGet(() -> new NotificationReadMarker(username));
            marker.readUpTo(max);
            markers.save(marker);
            reads.deleteCovered(username, marker.getLastReadId());
        });
    }

    @Override public void destroy() { emailWorker.shutdown(); }
}
