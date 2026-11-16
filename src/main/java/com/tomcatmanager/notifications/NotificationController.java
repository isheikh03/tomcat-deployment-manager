package com.tomcatmanager.notifications;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Notification centre (page) and the navbar bell API. Every signed-in user sees operational notifications. */
@Controller
public class NotificationController {
    private final NotificationService service;
    private final NotificationAccess visibility;
    private final com.tomcatmanager.access.Access access;

    public NotificationController(NotificationService service, NotificationAccess visibility, com.tomcatmanager.access.Access access) {
        this.service = service; this.visibility = visibility; this.access = access;
    }

    /** Only notifications about the user's teams, hosts, instances and deployments. */
    private java.util.function.Predicate<Notification> mine() { return visibility.visibleTo(access.current()); }

    @GetMapping("/notifications")
    public String index(@RequestParam(required = false) Notification.Category category, @RequestParam(defaultValue = "0") int page,
                        Principal principal, Model model) {
        Page<Notification> notifications = service.page(category, page, mine());
        model.addAttribute("pageTitle", "Notifications");
        model.addAttribute("notifications", notifications);
        model.addAttribute("category", category);
        model.addAttribute("categories", Notification.Category.values());
        model.addAttribute("readIds", service.readIds(principal.getName(), notifications.map(Notification::getId).getContent()));
        return "notifications/index";
    }

    public record NotificationDto(Long id, Instant createdAt, Notification.Level level, String badgeClass, Notification.Category category,
                                  String title, String message, String link, boolean unread) { }

    public record BellDto(long unread, List<NotificationDto> recent) { }

    @GetMapping("/api/notifications")
    @ResponseBody
    public BellDto bell(Principal principal) {
        java.util.function.Predicate<Notification> mine = mine();
        List<Notification> recent = service.recent(mine);
        Set<Long> read = service.readIds(principal.getName(), recent.stream().map(Notification::getId).toList());
        return new BellDto(service.unreadCount(principal.getName(), mine), recent.stream()
                .map(n -> new NotificationDto(n.getId(), n.getCreatedAt(), n.getLevel(), n.getLevel().badgeClass(), n.getCategory(), n.getTitle(),
                        n.getMessage(), n.getLink(), !read.contains(n.getId()))).toList());
    }

    /** Marking one's own notifications as read is allowed for every signed-in user, including viewers. */
    @PostMapping("/api/notifications/read")
    @ResponseBody
    public BellDto markRead(Principal principal) {
        service.markAllRead(principal.getName());
        return bell(principal);
    }

    @PostMapping("/api/notifications/{id}/read")
    @ResponseBody
    public BellDto markOneRead(@PathVariable long id, Principal principal) {
        service.markRead(principal.getName(), id);
        return bell(principal);
    }
}
