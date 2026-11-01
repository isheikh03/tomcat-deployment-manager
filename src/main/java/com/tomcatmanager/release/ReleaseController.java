package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationRepository;
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

    public ReleaseController(ReleaseService service, ReleaseRepository releases, ApplicationRepository applications,
                             TomcatInstanceRepository instances, ArtifactStorage storage, StorageHousekeeping housekeeping,
                             StoredArtifactRepository artifacts, com.tomcatmanager.deployment.DeploymentRunRepository deploymentRuns) {
        this.service = service; this.releases = releases; this.applications = applications; this.instances = instances; this.storage = storage;
        this.housekeeping = housekeeping; this.artifacts = artifacts; this.deploymentRuns = deploymentRuns;
    }

    @GetMapping
    public String library(@RequestParam(required = false) Long app, @RequestParam(defaultValue = "") String q,
                          @RequestParam(defaultValue = "false") boolean archived, @RequestParam(defaultValue = "0") int page, Model model) {
        Page<Release> result = service.search(app, q, archived, page, PAGE_SIZE);
        model.addAttribute("pageTitle", "Release library");
        model.addAttribute("releases", result);
        model.addAttribute("applications", applications.findAllByOrderByNameAsc());
        model.addAttribute("app", app);
        model.addAttribute("q", q);
        model.addAttribute("archived", archived);
        model.addAttribute("filtered", app != null || !q.isBlank() || archived);
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
