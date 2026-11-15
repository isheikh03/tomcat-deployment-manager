package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.deployment.StorageCleanupService;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;

@Controller
@RequestMapping("/releases")
public class ReleaseController {
    private static final int PAGE_SIZE = 20;

    private final ReleaseService service;
    private final ReleaseRepository releases;
    private final ApplicationRepository applications;
    private final TomcatInstanceRepository instances;
    private final ArtifactStorage storage;
    private final StorageHousekeeping housekeeping;
    private final StoredArtifactRepository artifacts;
    private final com.tomcatmanager.deployment.DeploymentRunRepository deploymentRuns;
    private final StorageCleanupService cleanup;

    public ReleaseController(ReleaseService service, ReleaseRepository releases, ApplicationRepository applications,
                             TomcatInstanceRepository instances, ArtifactStorage storage, StorageHousekeeping housekeeping,
                             StoredArtifactRepository artifacts, com.tomcatmanager.deployment.DeploymentRunRepository deploymentRuns,
                             StorageCleanupService cleanup) {
        this.service = service; this.releases = releases; this.applications = applications; this.instances = instances; this.storage = storage;
        this.housekeeping = housekeeping; this.artifacts = artifacts; this.deploymentRuns = deploymentRuns; this.cleanup = cleanup;
    }

    /** Disk usage of the release store, shown to administrators above the library and on the cleanup page. */
    private void storageOverview(Model model) {
        model.addAttribute("storedSize", ArtifactStorage.humanSize(artifacts.totalStoredBytes()));
        model.addAttribute("storedCount", artifacts.countByFileRemovedAtIsNull());
        model.addAttribute("removedCount", artifacts.countByFileRemovedAtIsNotNull());
        long free = storage.usableSpace();
        model.addAttribute("freeSpace", free < 0 ? "unknown" : ArtifactStorage.humanSize(free));
    }

    @GetMapping
    public String library(@RequestParam(required = false) Long app, @RequestParam(defaultValue = "") String q,
                          @RequestParam(defaultValue = "false") boolean archived, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "") String tab, org.springframework.security.core.Authentication authentication, Model model) {
        Page<Release> result = service.search(app, q, archived, page, PAGE_SIZE);
        // Administrators clean up release storage on this page, in the "Storage cleanup" tab; the same filter applies.
        boolean admin = authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) {
            List<StorageCleanupService.ReleaseFile> files = cleanup.releaseFiles(app, q);
            model.addAttribute("files", files);
            model.addAttribute("removableCount", files.stream().filter(f -> f.state() == StorageCleanupService.FileState.STORED).count());
            model.addAttribute("storageTab", "storage".equals(tab));
        }
        model.addAttribute("pageTitle", "Release library");
        model.addAttribute("releases", result);
        model.addAttribute("applications", applications.findAllByOrderByNameAsc());
        model.addAttribute("app", app);
        model.addAttribute("q", q);
        model.addAttribute("archived", archived);
        model.addAttribute("filtered", app != null || !q.isBlank() || archived);
        storageOverview(model);
        housekeeping.lastReport().ifPresent(report -> {
            model.addAttribute("storageReport", report);
            model.addAttribute("missingReleases", report.missing().stream().map(artifacts::findBySha256).flatMap(java.util.Optional::stream)
                    .flatMap(a -> releases.findByArtifactIdOrderByUploadedAtDesc(a.getId()).stream()).toList());
        });
        return "releases/index";
    }

    @GetMapping("/upload")
    public String upload(@RequestParam(required = false) Long app, Model model) {
        model.addAttribute("pageTitle", "Upload WAR");
        model.addAttribute("applications", applications.findAllByOrderByNameAsc());
        model.addAttribute("app", app);
        model.addAttribute("maxBytes", storage.maxBytes());
        model.addAttribute("maxSize", ArtifactStorage.humanSize(storage.maxBytes()));
        return "releases/upload";
    }

    /** One row of the "compatibility with your instances" table. */
    public record CompatibilityRow(TomcatInstance instance, Compatibility.Result result) { }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        Release release = service.find(id);
        Compatibility.Requirements requirements = Compatibility.requirements(release.getApplication(), release.getArtifact());
        List<CompatibilityRow> rows = instances.findAllByOrderByNameAsc().stream()
                .map(i -> new CompatibilityRow(i, Compatibility.evaluate(requirements, i.getTomcatVersion(), i.getJavaVersion()))).toList();
        List<Release> sharing = releases.findByArtifactIdOrderByUploadedAtDesc(release.getArtifact().getId()).stream()
                .filter(r -> !r.getId().equals(id)).toList();
        model.addAttribute("pageTitle", release.getApplication().getName() + " " + release.getVersionLabel());
        model.addAttribute("release", release);
        model.addAttribute("artifact", release.getArtifact());
        model.addAttribute("size", ArtifactStorage.humanSize(release.getArtifact().getSizeBytes()));
        model.addAttribute("requirements", requirements);
        model.addAttribute("compatibility", rows);
        model.addAttribute("sharing", sharing);
        model.addAttribute("deployments", deploymentRuns.findByReleaseIdOrderByRequestedAtDesc(id));
        model.addAttribute("releaseFile", cleanup.describe(release));
        return "releases/detail";
    }

    @PostMapping("/{id}/metadata")
    public String updateMetadata(@PathVariable long id, @RequestParam long version, @RequestParam String versionLabel,
                                 @RequestParam(required = false) String releaseNotes, Principal principal, RedirectAttributes flash) {
        try {
            service.updateMetadata(id, version, versionLabel, releaseNotes, principal.getName());
            flash.addFlashAttribute("success", "Release details saved. The WAR file itself is unchanged.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        } catch (ObjectOptimisticLockingFailureException e) {
            flash.addFlashAttribute("error", "Someone else changed this release in the meantime. Review the current values and try again.");
        }
        return "redirect:/releases/" + id;
    }

    /** Runs the artifact-store consistency check immediately (administrators). */
    @PostMapping("/storage-check")
    public String storageCheck(RedirectAttributes flash) {
        StorageHousekeeping.Report report = housekeeping.run();
        if (report.error() != null) flash.addFlashAttribute("error", report.error());
        else if (!report.missing().isEmpty()) flash.addFlashAttribute("error", "Storage check found " + report.missing().size() + " missing file(s). See the warning below.");
        else flash.addFlashAttribute("success", "Storage check passed: every release file is present"
                + (report.tempFilesRemoved() + report.orphanFilesRemoved() > 0 ? "; removed " + report.tempFilesRemoved() + " temporary and " + report.orphanFilesRemoved() + " orphaned file(s)." : "."));
        return "redirect:/releases";
    }

    /** Removes the stored WAR file of a release to free space; the release and its history are kept (administrators). */
    @PostMapping("/{id}/remove-file")
    public String removeFile(@PathVariable long id, @RequestParam(required = false) String reason,
                             @RequestParam(defaultValue = "false") boolean confirmRollbackPoint, Principal principal, RedirectAttributes flash) {
        try {
            cleanup.removeReleaseFile(id, principal.getName(), reason, confirmRollbackPoint);
            flash.addFlashAttribute("success", "The WAR file was removed from storage. The release and its deployment history are kept; "
                    + "upload the identical WAR again to make it deployable.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/releases/" + id;
    }

    /** Storage cleanup is now a tab of the Release library; old links and bookmarks open that tab. */
    @GetMapping("/cleanup")
    public String cleanupSelect(@RequestParam(required = false) Long app, @RequestParam(defaultValue = "") String q, RedirectAttributes redirect) {
        storageTabParams(redirect, app, q);
        return "redirect:/releases";
    }

    private static void storageTabParams(RedirectAttributes redirect, Long app, String q) {
        if (app != null) redirect.addAttribute("app", app);
        if (q != null && !q.isBlank()) redirect.addAttribute("q", q);
        redirect.addAttribute("tab", "storage");
    }

    @PostMapping("/cleanup")
    public String cleanupConfirm(@RequestParam(name = "artifactIds", required = false) List<Long> artifactIds,
                                 @RequestParam(required = false) String reason, @RequestParam(required = false) Long app,
                                 @RequestParam(defaultValue = "") String q, Principal principal, RedirectAttributes flash) {
        try {
            StorageCleanupService.Result result = cleanup.removeReleaseFiles(artifactIds, principal.getName(), reason);
            flash.addFlashAttribute(result.removed() > 0 && result.problems().isEmpty() ? "success" : "error", result.message("WAR file"));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        storageTabParams(flash, app, q); // back to the same filtered list, on the Storage cleanup tab
        return "redirect:/releases";
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        service.setArchived(id, true, principal.getName());
        flash.addFlashAttribute("success", "Release archived. It is hidden from the library and cannot be chosen for new deployments.");
        return "redirect:/releases/" + id;
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        service.setArchived(id, false, principal.getName());
        flash.addFlashAttribute("success", "Release restored to the library.");
        return "redirect:/releases/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        Long appId = service.find(id).getApplication().getId();
        try { service.delete(id, principal.getName()); }
        catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); return "redirect:/releases/" + id; }
        flash.addFlashAttribute("success", "Release deleted.");
        return "redirect:/applications/" + appId;
    }
}
