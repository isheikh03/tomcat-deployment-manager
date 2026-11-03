package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ApplicationForm;
import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.application.ApplicationService;
import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.InvalidWarException;
import com.tomcatmanager.release.QuickReleaseNaming;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Quick Deployment: turns an uploaded WAR into a deployable release without the separate
 * "register application" and "upload release" steps. The application is found by name or created
 * (administrators only) and the version is derived from the file. Deploying then goes through the
 * normal preflight and engine, so backups, health checks, rollback and audit work as for any deployment.
 */
@Service
public class QuickDeploymentService {
    private final ReleaseService releases;
    private final ApplicationRepository applications;
    private final ApplicationService applicationService;
    private final TomcatInstanceRepository instances;
    private final DeploymentTargetRepository targets;

    public QuickDeploymentService(ReleaseService releases, ApplicationRepository applications, ApplicationService applicationService,
                                  TomcatInstanceRepository instances, DeploymentTargetRepository targets) {
        this.releases = releases; this.applications = applications; this.applicationService = applicationService;
        this.instances = instances; this.targets = targets;
    }

    /** The release ready to deploy, and the WAR file name each instance would receive by default. */
    public record Prepared(Long releaseId, Long applicationId, String applicationName, boolean applicationCreated, String versionLabel,
                           boolean reusedRelease, String sha256, String size, Integer detectedJavaVersion, String servletApi,
                           Map<Long, String> filenames) { }

    /**
     * @param applicationName optional; derived from the file name when empty
     * @param versionLabel    optional; derived from the file name, manifest or upload time when empty
     */
    public Prepared prepare(String applicationName, String versionLabel, String originalFilename, InputStream content, String actor,
                            boolean mayCreateApplication) throws IOException, InvalidWarException {
        if (originalFilename == null || originalFilename.isBlank()) throw new IllegalArgumentException("The uploaded file has no name.");
        String name = applicationName == null || applicationName.isBlank() ? QuickReleaseNaming.applicationName(originalFilename) : applicationName.strip();
        if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("The application name must be 1 to 120 characters.");

        AtomicReference<ManagedApplication> application = new AtomicReference<>();
        AtomicBoolean created = new AtomicBoolean();
        ReleaseService.QuickUpload upload = releases.uploadForQuickDeployment(() -> {
            application.set(resolve(name, mayCreateApplication, actor, created));
            return application.get();
        }, versionLabel, originalFilename, content, actor);

        Release release = upload.release();
        ManagedApplication app = application.get();
        String fallback = QuickReleaseNaming.deploymentFilename(app.getName());
        Map<Long, String> filenames = new LinkedHashMap<>();
        for (TomcatInstance i : instances.findAllByOrderByNameAsc())
            filenames.put(i.getId(), targets.findLastFilename(i.getId(), app.getId()).orElse(fallback));
        return new Prepared(release.getId(), app.getId(), app.getName(), created.get(), release.getVersionLabel(), upload.reused(),
                release.getArtifact().getSha256(), ArtifactStorage.humanSize(release.getArtifact().getSizeBytes()),
                release.getArtifact().getDetectedJavaVersion(), release.getArtifact().getDetectedServletApi().name(), filenames);
    }

    /** Existing application by name (case-insensitive), or a new one with detected compatibility. */
    private ManagedApplication resolve(String name, boolean mayCreate, String actor, AtomicBoolean created) {
        var existing = applications.findByNameIgnoreCase(name);
        if (existing.isPresent()) return existing.get();
        if (!mayCreate) throw new IllegalArgumentException("There is no application named " + name
                + ". Choose an existing application, or ask an administrator to register it.");
        ApplicationForm form = new ApplicationForm();
        form.setName(name);
        form.setServletApi(ManagedApplication.ServletApi.DETECT);
        form.setDescription("Created by Quick Deployment.");
        try {
            ManagedApplication saved = applicationService.save(form, actor);
            created.set(true);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // created a moment ago by a concurrent quick deployment of the same application
            return applications.findByNameIgnoreCase(name).orElseThrow(() -> e);
        }
    }
}
