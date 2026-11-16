package com.tomcatmanager.infrastructure;

import com.tomcatmanager.security.SecretEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;

/** Tomcat instance configuration rules. */
@Service
public class InstanceService {
    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final TomcatInstanceRepository instances;
    private final ManagedHostRepository hosts;
    private final ServerGroupRepository groups;
    private final SecretEncryptionService encryption;

    public InstanceService(TomcatInstanceRepository instances, ManagedHostRepository hosts, ServerGroupRepository groups, SecretEncryptionService encryption) {
        this.instances = instances; this.hosts = hosts; this.groups = groups; this.encryption = encryption;
    }

    public TomcatInstance find(long id) { return instances.findById(id).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + id + " not found.")); }

    public void validate(InstanceForm f, Errors errors) {
        TomcatInstance existing = f.getId() == null ? null : find(f.getId());
        if (existing != null) f.setManagerSecretStored(existing.hasManagerSecret());

        if (f.getHostId() != null && hosts.findById(f.getHostId()).isEmpty()) errors.rejectValue("hostId", "invalid", "Select a valid host.");
        if (f.getHostId() != null && f.getName() != null && !f.getName().isBlank()) {
            boolean nameTaken = instances.findByHostIdOrderByNameAsc(f.getHostId()).stream()
                    .anyMatch(i -> i.getName().equalsIgnoreCase(f.getName().trim()) && !i.getId().equals(f.getId()));
            if (nameTaken) errors.rejectValue("name", "duplicate", "This host already has an instance with this name.");
        }
        if (f.getCatalinaBase() != null && !f.getCatalinaBase().isBlank() && !InfrastructureValidation.isSafeAbsolutePath(f.getCatalinaBase().trim()))
            errors.rejectValue("catalinaBase", "invalid", "Use an absolute path such as /opt/tomcat/instance-a (letters, digits, . _ - @ + / only; no '..').");
        if (f.getAppBase() != null && !f.getAppBase().isBlank() && !InfrastructureValidation.isSafePath(f.getAppBase().trim()))
            errors.rejectValue("appBase", "invalid", "Use 'webapps' or an absolute path (letters, digits, . _ - @ + / only; no '..').");
        if (f.isHealthCheckEnabled() && (f.getHealthUrl() == null || f.getHealthUrl().isBlank()))
            errors.rejectValue("healthUrl", "required", "Enter the application health URL, or switch off the health check.");
        else if (f.getHealthUrl() != null && !f.getHealthUrl().isBlank() && !isHttpUrl(f.getHealthUrl().trim().replace("{context}", "/app")))
            errors.rejectValue("healthUrl", "invalid", "Enter an absolute http:// or https:// URL. You may use {context} for the deployed context path, e.g. http://host:8080{context}/health.");

        String statusProblem = com.tomcatmanager.health.HealthExpectation.statusProblem(f.getHealthExpectedStatus());
        if (statusProblem != null) errors.rejectValue("healthExpectedStatus", "invalid", statusProblem);

        if (f.getTomcatVersion() != null && !f.getTomcatVersion().isBlank() && !f.getTomcatVersion().trim().matches("^\\d{1,2}(\\.\\d{1,3}){0,2}$"))
            errors.rejectValue("tomcatVersion", "invalid", "Use a version such as 10.1 or 10.1.30.");

        if (f.getEnvironment() == null) errors.rejectValue("environment", "required", "Choose the environment this Tomcat belongs to.");
        String filenameProblem = DeploymentFilename.problem(f.getDeploymentFilename());
        if (filenameProblem != null) {
            errors.rejectValue("deploymentFilename", "invalid", filenameProblem);
        } else if (f.getHostId() != null && f.getCatalinaBase() != null && f.getAppBase() != null) {
            // Another instance deploying into the same directory must not use the same file or context.
            String dir = directory(f.getCatalinaBase(), f.getAppBase());
            String context = DeploymentFilename.contextPath(f.getDeploymentFilename());
            instances.findByHostIdOrderByNameAsc(f.getHostId()).stream()
                    .filter(i -> !i.getId().equals(f.getId()) && i.getDeploymentFilename() != null && dir.equals(i.getRemoteDeploymentDirectory()))
                    .filter(i -> i.getDeploymentFilename().equalsIgnoreCase(f.getDeploymentFilename().trim()) || context.equals(i.getDeploymentContextPath()))
                    .findFirst()
                    .ifPresent(i -> errors.rejectValue("deploymentFilename", "conflict",
                            "Instance '" + i.getName() + "' already deploys " + i.getDeploymentFilename() + " (context " + i.getDeploymentContextPath() + ") into " + dir + "."));
        }

        ManagerUrl url = null;
        if (f.getManagerUrl() != null && !f.getManagerUrl().isBlank()) {
            try { url = ManagerUrl.parse(f.getManagerUrl()); }
            catch (IllegalArgumentException e) { errors.rejectValue("managerUrl", "invalid", e.getMessage()); }
        }
        boolean newSecret = f.getManagerSecret() != null && !f.getManagerSecret().isBlank();
        if (existing != null && existing.hasManagerSecret() && !newSecret && !f.isClearManagerSecret() && url != null
                && (!url.equals(ManagerUrl.of(existing)) || !String.valueOf(f.getManagerUsername()).trim().equals(existing.getManagerUsername())))
            errors.rejectValue("managerSecret", "reenter", "The Manager URL or user changed: enter the password again so the stored one is not sent to a different server.");
        if (newSecret && f.isClearManagerSecret())
            errors.rejectValue("managerSecret", "conflict", "Either remove the stored password or provide a new one, not both.");
        if (f.getManagerSecret() != null && !f.getManagerSecret().isBlank() && !encryption.isConfigured())
            errors.reject("encryption", "TDL_APP_ENCRYPTION_KEY is not configured, so credentials cannot be stored.");

        if (f.getDeploymentMethod() == TomcatInstance.DeploymentMethod.TOMCAT_MANAGER) {
            if (f.getManagerUsername() == null || f.getManagerUsername().isBlank())
                errors.rejectValue("managerUsername", "required", "A Manager username is required for Tomcat Manager deployment.");
            boolean willHaveSecret = (f.getManagerSecret() != null && !f.getManagerSecret().isBlank()) || (f.isManagerSecretStored() && !f.isClearManagerSecret());
            if (!willHaveSecret) errors.rejectValue("managerSecret", "required", "A Manager password is required for Tomcat Manager deployment.");
            if ((f.getManagerUrl() == null || f.getManagerUrl().isBlank()) && !errors.hasFieldErrors("managerUrl"))
                errors.rejectValue("managerUrl", "required", "Enter the Manager URL, e.g. https://tomcat01.example.com:8443/manager/text.");
        }
    }

    @Transactional
    public TomcatInstance save(InstanceForm f) { return saveWithChanges(f).instance(); }

    /** What a save changed, for the audit log and notifications. Never contains secret values. */
    public record InstanceChange(TomcatInstance instance, boolean created, boolean connectionChanged, String credentials) { }

    @Transactional
    public InstanceChange saveWithChanges(InstanceForm f) {
        TomcatInstance i = f.getId() == null ? new TomcatInstance() : find(f.getId());
        boolean created = f.getId() == null;
        String before = connectionKey(i);
        i.setName(f.getName()); i.setHost(hosts.findById(f.getHostId()).orElseThrow());
        i.setCatalinaBase(stripTrailingSlash(f.getCatalinaBase())); i.setAppBase(stripTrailingSlash(f.getAppBase()));
        i.setBackupEnabled(f.isBackupEnabled()); i.setHealthCheckEnabled(f.isHealthCheckEnabled()); i.setHealthUrl(f.getHealthUrl()); i.setHttpPort(f.getHttpPort());
        i.setStartupTimeoutSeconds(f.getStartupTimeoutSeconds()); i.setHealthConsecutiveSuccesses(f.getHealthConsecutiveSuccesses());
        i.setHealthExpectedStatus(f.getHealthExpectedStatus()); i.setHealthExpectedText(f.getHealthExpectedText()); i.setMonitoringEnabled(f.isMonitoringEnabled());
        i.setDeploymentFilename(f.getDeploymentFilename());
        i.setEnvironment(f.getEnvironment());
        i.setTomcatVersion(f.getTomcatVersion()); i.setJavaVersion(f.getJavaVersion());
        i.setDeploymentMethod(f.getDeploymentMethod());
        if (f.getManagerUrl() != null && !f.getManagerUrl().isBlank()) {
            ManagerUrl url = ManagerUrl.parse(f.getManagerUrl());
            i.setManagerProtocol(url.protocol()); i.setManagerHost(url.host()); i.setManagerPort(url.port()); i.setManagerEndpoint(url.path());
        }
        i.setManagerUsername(blankToNull(f.getManagerUsername()));
        i.setTimeoutSeconds(f.getTimeoutSeconds()); i.setMaxRetries(f.getMaxRetries()); i.setEnabled(f.isEnabled());
        String credentials = null;
        if (f.isClearManagerSecret()) {
            i.setManagerSecretEncrypted(null);
            credentials = "Tomcat Manager password removed";
            log.info("Tomcat Manager password removed for instance {}", f.getId());
        } else if (f.getManagerSecret() != null && !f.getManagerSecret().isBlank()) {
            i.setManagerSecretEncrypted(encryption.encrypt(f.getManagerSecret()));
            credentials = "Tomcat Manager password replaced";
            log.info("Tomcat Manager password replaced for instance {}", f.getId());
        }
        List<Long> groupIds = f.getGroupIds() == null ? List.of() : f.getGroupIds();
        // A group belongs to one team: only groups of the host's team can contain this instance.
        Long team = i.getHost().getTeamId();
        i.setGroups(new LinkedHashSet<>(groups.findAllById(groupIds).stream().filter(g -> java.util.Objects.equals(g.getTeamId(), team)).toList()));
        TomcatInstance saved = instances.save(i);
        return new InstanceChange(saved, created, !created && (!before.equals(connectionKey(saved)) || credentials != null), credentials);
    }

    /** Settings that decide how the tool connects to and deploys on the instance. */
    private static String connectionKey(TomcatInstance i) {
        return i.getHost() == null ? "" : i.getHost().getId() + "|" + i.getDeploymentMethod() + "|" + i.getRemoteDeploymentDirectory() + "|" + i.getDeploymentFilename()
                + "|" + i.getManagerProtocol() + "|" + i.getManagerHost() + "|" + i.getManagerPort() + "|" + i.getManagerEndpoint() + "|" + i.getManagerUsername()
                + "|" + i.getHealthUrl() + "|" + i.isEnabled() + "|" + i.getEnvironment();
    }

    @Transactional
    public void delete(long id) { instances.delete(find(id)); }

    private static boolean isHttpUrl(String s) {
        try {
            URI uri = URI.create(s);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
    private static String directory(String catalinaBase, String appBase) {
        TomcatInstance probe = new TomcatInstance();
        probe.setCatalinaBase(stripTrailingSlash(catalinaBase)); probe.setAppBase(stripTrailingSlash(appBase));
        return probe.getRemoteDeploymentDirectory();
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
