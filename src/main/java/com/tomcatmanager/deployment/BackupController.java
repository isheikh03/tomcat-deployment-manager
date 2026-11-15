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

/** Backup management page and API. Verify: ADMIN or DEPLOYMENT_OPERATOR; delete: ADMIN; reading: signed-in users. */
@Controller
public class BackupController {
    private final BackupService service;
    private final DeploymentProperties properties;
    private final StorageCleanupService cleanup;

    public BackupController(BackupService service, DeploymentProperties properties, StorageCleanupService cleanup) {
        this.service = service; this.properties = properties; this.cleanup = cleanup;
    }

    @GetMapping("/backups")
    public String index(Model model) {
        List<BackupService.Entry> entries = service.list();
        model.addAttribute("pageTitle", "Backups");
        model.addAttribute("entries", entries);
        model.addAttribute("serverBackupCount", entries.stream().filter(BackupService.Entry::serverBackup).count());
        model.addAttribute("protectedCount", entries.stream().filter(BackupService.Entry::rollbackPoint).count());
        model.addAttribute("missingCount", entries.stream().filter(e -> !e.fileExists()).count());
        model.addAttribute("totalSize", ArtifactStorage.humanSize(service.totalServerBackupBytes()));
        model.addAttribute("backupDir", properties.backupDir().toAbsolutePath().normalize().toString());
        model.addAttribute("retention", service.retentionPerContext());
        model.addAttribute("freeSpace", freeSpace());
        return "backups/index";
    }

    private String freeSpace() {
        try { return ArtifactStorage.humanSize(java.nio.file.Files.getFileStore(properties.backupDir().toAbsolutePath().normalize()).getUsableSpace()); }
        catch (java.io.IOException | RuntimeException e) { return "unknown"; }
    }

    /** Server backups, optionally of one instance, to select individual backups for deletion (administrators). Nothing is preselected. */
    @GetMapping("/backups/cleanup")
    public String cleanupSelect(@RequestParam(required = false) Long instance, Model model) {
        model.addAttribute("pageTitle", "Clean up backups");
        model.addAttribute("entries", cleanup.serverBackups(instance));
        model.addAttribute("instances", cleanup.backupInstances());
        model.addAttribute("instance", instance);
        model.addAttribute("totalSize", ArtifactStorage.humanSize(service.totalServerBackupBytes()));
        model.addAttribute("freeSpace", freeSpace());
        return "backups/cleanup";
    }

    @PostMapping("/backups/cleanup")
    public String cleanupConfirm(@RequestParam(name = "backupIds", required = false) List<Long> backupIds, @RequestParam(required = false) String reason,
                                 @RequestParam(required = false) Long instance, Principal principal, RedirectAttributes flash) {
        try {
            StorageCleanupService.Result result = cleanup.deleteBackups(backupIds, principal.getName(), reason);
            flash.addFlashAttribute(result.removed() > 0 && result.problems().isEmpty() ? "success" : "error", result.message("backup"));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        if (instance != null) flash.addAttribute("instance", instance); // back to the same filtered list
        return "redirect:/backups/cleanup";
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
    public List<BackupDto> list() { return service.list().stream().map(BackupDto::from).toList(); }

    @PostMapping("/api/backups/{id}/verify")
    @ResponseBody
    public ArtifactStorage.Verification verify(@PathVariable long id, Principal principal) { return service.verify(id, principal.getName()); }

    /** {@code confirmRollbackPoint} is required to delete the current rollback point of a deployment. */
    public record DeleteRequest(String reason, Boolean confirmRollbackPoint) { }

    @PostMapping("/api/backups/{id}/delete")
    @ResponseBody
    public java.util.Map<String, Object> delete(@PathVariable long id, @RequestBody DeleteRequest body, Principal principal) {
        service.delete(id, principal.getName(), body == null ? null : body.reason(), body != null && Boolean.TRUE.equals(body.confirmRollbackPoint()));
        return java.util.Map.of("deleted", true, "id", id);
    }
}
