package com.tomcatmanager.deployment;

import com.tomcatmanager.release.ArtifactStorage;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * Backup management page and API. Everyone sees the backups of the Tomcat instances they can see; verifying needs deploy
 * access to the instance; deleting needs administering the instance's team.
 */
@Controller
public class BackupController {
    private final BackupService service;
    private final DeploymentProperties properties;
    private final StorageCleanupService cleanup;
    private final com.tomcatmanager.access.Access access;
    private final com.tomcatmanager.infrastructure.TomcatInstanceRepository instanceRepository;

    public BackupController(BackupService service, DeploymentProperties properties, StorageCleanupService cleanup,
                            com.tomcatmanager.access.Access access, com.tomcatmanager.infrastructure.TomcatInstanceRepository instanceRepository) {
        this.service = service; this.properties = properties; this.cleanup = cleanup; this.access = access; this.instanceRepository = instanceRepository;
    }

    /** Backups of the instances the user can see. */
    private List<BackupService.Entry> visible(com.tomcatmanager.access.AccessScope s) {
        return service.list().stream().filter(e -> e.target().getInstanceId() != null && s.canViewInstance(e.target().getInstanceId())).toList();
    }

    private BackupService.Entry visibleEntry(long backupId, com.tomcatmanager.access.AccessScope s) {
        return visible(s).stream().filter(e -> e.backup().getId().equals(backupId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Backup " + backupId + " not found."));
    }

    /** Deleting a backup: administrators of the team its instance belongs to. */
    private boolean canDelete(BackupService.Entry e, com.tomcatmanager.access.AccessScope s) {
        if (s.isEverything()) return true;
        return instanceRepository.findById(e.target().getInstanceId()).map(i -> s.canManageInstance(i.getHost().getTeamId())).orElse(false);
    }

    /**
     * Restore points, laid out like the Release library: a "Restore points" tab listing every restore point (server
     * backups deleted to free space stay listed as "File removed"), and for administrators a "Storage cleanup" tab
     * where they select server backups and delete their files. Both tabs follow the same instance and search filter.
     */
    @GetMapping("/backups")
    public String index(@RequestParam(required = false) Long instance, @RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "") String tab, Model model) {
        com.tomcatmanager.access.AccessScope s = access.current();
        List<BackupService.Entry> everything = service.listIncludingRemoved().stream()
                .filter(e -> e.target().getInstanceId() != null && s.canViewInstance(e.target().getInstanceId())).toList();
        // Instances that have restore points, for the filter: id -> name.
        java.util.Map<Long, String> instances = new java.util.TreeMap<>();
        for (BackupService.Entry e : everything) instances.putIfAbsent(e.target().getInstanceId(), e.target().getInstanceName());
        Long selected = instance != null && instances.containsKey(instance) ? instance : null;
        String text = q.strip().toLowerCase(java.util.Locale.ROOT);
        List<BackupService.Entry> entries = everything.stream()
                .filter(e -> selected == null || selected.equals(e.target().getInstanceId()))
                .filter(e -> text.isEmpty() || (e.target().getInstanceName() + " " + e.target().getContextPath() + " " + e.target().getTargetFilename()
                        + " #" + e.target().getRun().getId()).toLowerCase(java.util.Locale.ROOT).contains(text))
                .toList();
        List<BackupService.Entry> current = everything.stream().filter(e -> !e.removed()).toList();
        // Backups the user may delete (their teams' instances); only these appear in the Storage cleanup tab.
        java.util.Set<Long> deletable = current.stream().filter(e -> e.serverBackup() && canDelete(e, s)).map(e -> e.backup().getId())
                .collect(java.util.stream.Collectors.toSet());
        // Backups a rollback is restoring right now: locked until it has finished.
        java.util.Set<Long> restoring = service.beingRestored();
        List<BackupService.Entry> files = entries.stream().filter(e -> !e.removed() && deletable.contains(e.backup().getId())).toList();

        model.addAttribute("pageTitle", "Restore points");
        model.addAttribute("entries", entries);
        model.addAttribute("files", files);
        model.addAttribute("removableCount", files.stream().filter(e -> !e.rollbackPoint() && !restoring.contains(e.backup().getId())).count());
        model.addAttribute("storageTab", "storage".equals(tab) && s.isAdministrator());
        model.addAttribute("instances", instances);
        model.addAttribute("instance", selected);
        model.addAttribute("q", q.strip());
        model.addAttribute("filtered", selected != null || !text.isEmpty());
        // The storage line describes all backups the user can see, whatever the filter.
        model.addAttribute("serverBackupCount", current.stream().filter(BackupService.Entry::serverBackup).count());
        model.addAttribute("removedCount", everything.stream().filter(e -> e.serverBackup() && e.removed()).count());
        model.addAttribute("protectedCount", current.stream().filter(BackupService.Entry::rollbackPoint).count());
        model.addAttribute("missingCount", current.stream().filter(e -> !e.fileExists()).count());
        model.addAttribute("totalSize", ArtifactStorage.humanSize(s.isEverything() ? service.totalServerBackupBytes()
                : current.stream().filter(BackupService.Entry::serverBackup).mapToLong(e -> e.backup().getSizeBytes() == null ? 0 : e.backup().getSizeBytes()).sum()));
        model.addAttribute("deletable", deletable);
        model.addAttribute("restoring", restoring);
        model.addAttribute("backupDir", properties.backupDir().toAbsolutePath().normalize().toString());
        model.addAttribute("retention", service.retentionPerContext());
        model.addAttribute("freeSpace", freeSpace());
        return "backups/index";
    }

    private String freeSpace() {
        try { return ArtifactStorage.humanSize(java.nio.file.Files.getFileStore(properties.backupDir().toAbsolutePath().normalize()).getUsableSpace()); }
        catch (java.io.IOException | RuntimeException e) { return "unknown"; }
    }

    /** Backups are now selected and deleted on the Restore points page; old links and bookmarks land there. */
    @GetMapping("/backups/cleanup")
    public String cleanupSelect(@RequestParam(required = false) Long instance, RedirectAttributes redirect) {
        if (instance != null) redirect.addAttribute("instance", instance);
        redirect.addAttribute("tab", "storage");
        return "redirect:/backups";
    }

    @PostMapping("/backups/cleanup")
    public String cleanupConfirm(@RequestParam(name = "backupIds", required = false) List<Long> backupIds, @RequestParam(required = false) String reason,
                                 @RequestParam(required = false) Long instance, @RequestParam(defaultValue = "") String q,
                                 @RequestParam(defaultValue = "false") boolean confirmProtected, Principal principal, RedirectAttributes flash) {
        // Every selected backup must be one the user may delete (their teams' instances); otherwise nothing is deleted.
        com.tomcatmanager.access.AccessScope s = access.current();
        if (backupIds != null) for (Long id : backupIds)
            if (!canDelete(visibleEntry(id, s), s)) throw new com.tomcatmanager.access.NotPermittedException("You can delete only backups of your teams' instances.");
        try {
            // confirmProtected: the selection includes current rollback points and the user confirmed losing them.
            StorageCleanupService.Result result = cleanup.deleteBackups(backupIds, principal.getName(), reason, confirmProtected);
            flash.addFlashAttribute(result.removed() > 0 && result.problems().isEmpty() ? "success" : "error", result.message("backup"));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        // Back to the same filtered list, on the Storage cleanup tab.
        if (instance != null) flash.addAttribute("instance", instance);
        if (!q.isBlank()) flash.addAttribute("q", q.strip());
        flash.addAttribute("tab", "storage");
        return "redirect:/backups";
    }

    public record BackupDto(Long id, Long targetId, Long runId, String instanceName, String contextPath, String filename, String source,
                            Long sizeBytes, String sha256, Instant createdAt, Instant lastVerifiedAt, Boolean lastVerificationOk,
                            boolean rollbackPoint, boolean fileExists) {
        static BackupDto from(BackupService.Entry e) {
            DeploymentBackup b = e.backup();
            return new BackupDto(b.getId(), b.getTargetId(), e.target().getRun().getId(), e.target().getInstanceName(), e.target().getContextPath(),
                    e.target().getTargetFilename(), b.getSource().name(), b.getSizeBytes(), b.getSha256(), b.getCreatedAt(),
                    b.getLastVerifiedAt(), b.getLastVerificationOk(), e.rollbackPoint(), e.fileExists());
        }
    }

    @GetMapping("/api/backups")
    @ResponseBody
    public List<BackupDto> list() { return visible(access.current()).stream().map(BackupDto::from).toList(); }

    @PostMapping("/api/backups/{id}/verify")
    @ResponseBody
    public ArtifactStorage.Verification verify(@PathVariable long id, Principal principal) {
        com.tomcatmanager.access.AccessScope s = access.current();
        BackupService.Entry e = visibleEntry(id, s);
        if (!s.canDeployTo(e.target().getInstanceId()))
            throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to " + e.target().getInstanceName() + " to verify its backups.");
        return service.verify(id, principal.getName());
    }

    /** {@code confirmRollbackPoint} is required to delete the current rollback point of a deployment. */
    public record DeleteRequest(String reason, Boolean confirmRollbackPoint) { }

    @PostMapping("/api/backups/{id}/delete")
    @ResponseBody
    public java.util.Map<String, Object> delete(@PathVariable long id, @RequestBody DeleteRequest body, Principal principal) {
        com.tomcatmanager.access.AccessScope s = access.current();
        if (!canDelete(visibleEntry(id, s), s)) throw new com.tomcatmanager.access.NotPermittedException("You can delete only backups of your teams' instances.");
        service.delete(id, principal.getName(), body == null ? null : body.reason(), body != null && Boolean.TRUE.equals(body.confirmRollbackPoint()));
        return java.util.Map.of("deleted", true, "id", id);
    }
}
