package com.tomcatmanager.notifications;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/** Notification centre (page) and the navbar bell API. Every signed-in user sees operational notifications. */
@Controller
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping("/notifications")
    public String index(@RequestParam(required = false) Notification.Category category, @RequestParam(defaultValue = "0") int page,
                        Principal principal, Model model) {
        model.addAttribute("pageTitle", "Notifications");
        model.addAttribute("notifications", service.page(category, page));
        model.addAttribute("category", category);
        model.addAttribute("categories", Notification.Category.values());
        model.addAttribute("lastRead", service.lastRead(principal.getName()));
        return "notifications/index";
    }

    public record NotificationDto(Long id, Instant createdAt, Notification.Level level, String badgeClass, Notification.Category category,
                                  String title, String message, String link, boolean unread) { }

    public record BellDto(long unread, List<NotificationDto> recent) { }

    @GetMapping("/api/notifications")
    @ResponseBody
    public BellDto bell(Principal principal) {
        long lastRead = service.lastRead(principal.getName());
        return new BellDto(service.unreadCount(principal.getName()), service.recent().stream()
                .map(n -> new NotificationDto(n.getId(), n.getCreatedAt(), n.getLevel(), n.getLevel().badgeClass(), n.getCategory(), n.getTitle(),
                        n.getMessage(), n.getLink(), n.getId() > lastRead)).toList());
    }

    /** Marking one's own notifications as read is allowed for every signed-in user, including viewers. */
    @PostMapping("/api/notifications/read")
    @ResponseBody
    public BellDto markRead(Principal principal) {
        service.markAllRead(principal.getName());
        return bell(principal);
    }
}
