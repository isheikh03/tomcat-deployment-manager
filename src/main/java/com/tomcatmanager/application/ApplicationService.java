package com.tomcatmanager.application;

import com.tomcatmanager.release.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applications;
    private final ReleaseRepository releases;
    private final com.tomcatmanager.audit.AuditService audit;

    public ApplicationService(ApplicationRepository applications, ReleaseRepository releases, com.tomcatmanager.audit.AuditService audit) {
        this.applications = applications; this.releases = releases; this.audit = audit;
    }

    /** Compatibility requirements as one comparable, human-readable line. */
    private static String requirements(ManagedApplication a) {
        return "servlet API " + a.getServletApi() + ", Java " + (a.getMinJavaVersion() == null ? "any" : a.getMinJavaVersion()) + "–"
                + (a.getMaxJavaVersion() == null ? "any" : a.getMaxJavaVersion()) + ", Tomcat " + (a.getSupportedTomcatVersions() == null ? "any" : a.getSupportedTomcatVersions());
    }

    public ManagedApplication find(long id) {
        return applications.findById(id).orElseThrow(() -> new NoSuchElementException("Application " + id + " not found."));
    }

    /** The application, if the user may see it (otherwise: not found). */
    public ManagedApplication findVisible(long id, com.tomcatmanager.access.AccessScope s) {
        ManagedApplication a = find(id);
        if (!s.canViewApplication(a.getTeamId())) throw new NoSuchElementException("Application " + id + " not found.");
        return a;
    }

    /** The application, if the user administers its team (otherwise: not found or forbidden). */
    public ManagedApplication findManageable(long id, com.tomcatmanager.access.AccessScope s) {
        ManagedApplication a = findVisible(id, s);
        if (!s.canManageApplication(a.getTeamId()))
            throw new com.tomcatmanager.access.NotPermittedException("Only administrators of this application's team can change it.");
        return a;
    }

    /** Applications the user may see, matching the search. */
    public List<ManagedApplication> searchVisible(String query, com.tomcatmanager.access.AccessScope s) {
        return search(query).stream().filter(a -> s.canViewApplication(a.getTeamId())).toList();
    }

    public List<ManagedApplication> search(String query) {
        String q = query == null || query.isBlank() ? "" : "%" + query.trim().toLowerCase() + "%";
        return applications.search(q);
    }

    public void validate(ApplicationForm f, Errors errors) {
        if (f.getName() != null && !f.getName().isBlank()) {
            applications.findByNameIgnoreCase(f.getName().trim()).filter(a -> !a.getId().equals(f.getId()))
                    .ifPresent(a -> errors.rejectValue("name", "duplicate", "An application with this name already exists."));
        }
        if (f.getVersionMode() == ManagedApplication.VersionMode.AUTO) {
            String problem = com.tomcatmanager.release.VersionPattern.problem(f.getVersionPattern());
            if (problem != null) errors.rejectValue("versionPattern", "invalid", problem);
        }
        if (f.getMinJavaVersion() != null && f.getMaxJavaVersion() != null && f.getMaxJavaVersion() < f.getMinJavaVersion())
            errors.rejectValue("maxJavaVersion", "range", "The maximum Java version must not be lower than the minimum.");
        for (String line : f.getSupportedTomcatVersions())
            if (!ApplicationForm.TOMCAT_LINES.contains(line)) { errors.rejectValue("supportedTomcatVersions", "invalid", "Unknown Tomcat version " + line + "."); break; }
        boolean javaxOnlyLines = f.getSupportedTomcatVersions().stream().allMatch(v -> v.startsWith("8.") || v.startsWith("9."));
        boolean jakartaOnlyLines = f.getSupportedTomcatVersions().stream().noneMatch(v -> v.startsWith("8.") || v.startsWith("9."));
        if (!f.getSupportedTomcatVersions().isEmpty()) {
            if (f.getServletApi() == ManagedApplication.ServletApi.JAKARTA && javaxOnlyLines)
                errors.rejectValue("supportedTomcatVersions", "mismatch", "jakarta.servlet applications need Tomcat 10.0 or newer.");
            if (f.getServletApi() == ManagedApplication.ServletApi.JAVAX && jakartaOnlyLines)
                errors.rejectValue("supportedTomcatVersions", "mismatch", "javax.servlet applications need Tomcat 9.0 or older.");
        }
    }

    @Transactional
    /** "manual" or "automatic (1.0.{n})", for the audit log. */
    static String versioning(ManagedApplication a) {
        return a.isAutoVersion() ? "automatic (" + a.getVersionPattern() + ")" : "manual";
    }

    public ManagedApplication save(ApplicationForm f, String actor) {
        ManagedApplication a;
        if (f.getId() == null) {
            a = new ManagedApplication();
            a.markCreated(actor);
            a.setTeamId(f.getTeamId()); // chosen once, at registration
        } else {
            a = find(f.getId());
            if (a.getVersion() != f.getVersion())
                throw new ObjectOptimisticLockingFailureException(ManagedApplication.class, f.getId());
            a.markUpdated(actor);
        }
        String requirementsBefore = f.getId() == null ? null : requirements(a);
        a.setName(f.getName());
        a.setDescription(blankToNull(f.getDescription()));
        a.setServletApi(f.getServletApi());
        a.setMinJavaVersion(f.getMinJavaVersion());
        a.setMaxJavaVersion(f.getMaxJavaVersion());
        a.setSupportedTomcatVersions(ApplicationForm.TOMCAT_LINES.stream().filter(f.getSupportedTomcatVersions()::contains)
                .reduce((x, y) -> x + "," + y).orElse(null));
        a.setOtherRequirements(blankToNull(f.getOtherRequirements()));
        String versioningBefore = f.getId() == null ? null : versioning(a);
        a.setVersionMode(f.getVersionMode());
        // The pattern is kept when switching to manual, so it is there again when switching back.
        if (f.getVersionPattern() != null && !f.getVersionPattern().isBlank()) a.setVersionPattern(f.getVersionPattern());
        ManagedApplication saved = applications.save(a);
        if (versioningBefore != null && !versioningBefore.equals(versioning(saved)))
            audit.record(actor, "VERSIONING_CHANGED", "APPLICATION", saved.getId(), com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                    "Versioning of " + saved.getName() + " changed from " + versioningBefore + " to " + versioning(saved) + ".");
        log.info("Application {} '{}' {} by {}", saved.getId(), saved.getName(), f.getId() == null ? "created" : "updated", actor);
        String requirementsAfter = requirements(saved);
        audit.record(actor, f.getId() == null ? "APPLICATION_CREATED" : "APPLICATION_UPDATED", "APPLICATION", saved.getId(),
                com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS, (f.getId() == null ? "Registered " : "Updated ") + saved.getName() + " (" + requirementsAfter + ").");
        if (requirementsBefore != null && !requirementsBefore.equals(requirementsAfter))
            audit.record(actor, "COMPATIBILITY_CHANGED", "APPLICATION", saved.getId(), com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                    "Compatibility requirements of " + saved.getName() + " changed from " + requirementsBefore + " to " + requirementsAfter + ".");
        return saved;
    }

    @Transactional
    public void delete(long id, String actor) {
        ManagedApplication a = find(id);
        if (releases.countByApplicationId(id) > 0)
            throw new IllegalStateException("This application still has releases. Delete its releases first; applications with releases cannot be removed.");
        applications.delete(a);
        log.info("Application {} '{}' deleted by {}", id, a.getName(), actor);
        audit.record(actor, "APPLICATION_DELETED", "APPLICATION", id, com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS, "Deleted application " + a.getName() + ".");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
