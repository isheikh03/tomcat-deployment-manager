package com.tomcatmanager.infrastructure;

import com.tomcatmanager.access.Access;
import com.tomcatmanager.access.AccessScope;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.connectivity.SshConnectivityClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * "Find Tomcat instances" for a Linux host: scans the server read-only over SFTP ({@link TomcatScan}), suggests one
 * Tomcat instance per application that is not added yet, and saves the ones the administrator selects. Saved instances
 * have the required settings (including the environment, chosen per row); optional ones (health check, restart, groups) are completed later.
 */
@Controller
@RequestMapping("/infrastructure/hosts/{hostId}/discover")
public class InstanceDiscoveryController {
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final InstanceService instanceService;
    private final InfrastructureAudit audit;
    private final ConnectionTestService connections;
    private final SshConnectivityClient ssh;
    private final Access access;

    public InstanceDiscoveryController(ManagedHostRepository hosts, TomcatInstanceRepository instances, InstanceService instanceService,
                                       InfrastructureAudit audit, ConnectionTestService connections, SshConnectivityClient ssh, Access access) {
        this.hosts = hosts; this.instances = instances; this.instanceService = instanceService; this.audit = audit;
        this.connections = connections; this.ssh = ssh; this.access = access;
    }

    /** One suggested instance (form row). Display fields come back with the form so the page can be shown again. */
    public static class Row {
        private boolean selected;
        private String name, catalinaBase, appBase, deploymentFilename, tomcatVersion, healthUrl, details, group;
        private Integer httpPort, javaVersion;
        private Environment environment;
        private boolean exploded;
        public boolean isSelected() { return selected; } public void setSelected(boolean v) { selected = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getCatalinaBase() { return catalinaBase; } public void setCatalinaBase(String v) { catalinaBase = v; }
        public String getAppBase() { return appBase; } public void setAppBase(String v) { appBase = v; }
        public String getDeploymentFilename() { return deploymentFilename; } public void setDeploymentFilename(String v) { deploymentFilename = v; }
        public String getTomcatVersion() { return tomcatVersion; } public void setTomcatVersion(String v) { tomcatVersion = v; }
        public String getHealthUrl() { return healthUrl; } public void setHealthUrl(String v) { healthUrl = v; }
        public String getDetails() { return details; } public void setDetails(String v) { details = v; }
        public String getGroup() { return group; } public void setGroup(String v) { group = v; }
        public Integer getHttpPort() { return httpPort; } public void setHttpPort(Integer v) { httpPort = v; }
        public Integer getJavaVersion() { return javaVersion; } public void setJavaVersion(Integer v) { javaVersion = v; }
        public Environment getEnvironment() { return environment; } public void setEnvironment(Environment v) { environment = v; }
        public boolean isExploded() { return exploded; } public void setExploded(boolean v) { exploded = v; }
    }

    public static class Selection {
        private List<Row> rows = new ArrayList<>();
        public List<Row> getRows() { return rows; } public void setRows(List<Row> v) { rows = v == null ? new ArrayList<>() : v; }
    }

    /** The host, if the user administers its team (otherwise not found or forbidden). */
    private ManagedHost manageableHost(long id) {
        AccessScope s = access.current();
        ManagedHost host = hosts.findById(id).orElseThrow(() -> new NoSuchElementException("Linux host " + id + " not found."));
        if (!s.canViewHost(host.getId(), host.getTeamId())) throw new NoSuchElementException("Linux host " + id + " not found.");
        if (!s.canManageHost(host.getTeamId()))
            throw new com.tomcatmanager.access.NotPermittedException("Only administrators of this host's team can add its Tomcat instances.");
        return host;
    }

    @GetMapping
    public String start(@PathVariable long hostId, Model model) {
        ManagedHost host = manageableHost(hostId);
        model.addAttribute("pageTitle", "Find Tomcat instances");
        model.addAttribute("host", host);
        return "infrastructure/discover";
    }

    /** Scans the server (read-only) and shows the suggestions. */
    @PostMapping
    public String scan(@PathVariable long hostId, Model model) {
        ManagedHost host = manageableHost(hostId);
        model.addAttribute("pageTitle", "Find Tomcat instances");
        model.addAttribute("host", host);
        if (!host.hasPinnedHostKey()) {
            model.addAttribute("scanError", "The server's host key is not pinned yet. Run a connection test on the host and accept its key first.");
            return "infrastructure/discover";
        }
        TomcatScan.Result result;
        try (ClientSession session = ssh.openSession(connections.sshTargetFor(host, Duration.ofSeconds(20)));
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            result = TomcatScan.scan(sftp, Duration.ofSeconds(60));
        } catch (IOException | RuntimeException e) {
            model.addAttribute("scanError", "Could not scan " + host.getHostname() + ": " + DiagnosticSanitizer.describe(e));
            return "infrastructure/discover";
        }
        Suggestions suggestions = suggest(host, result);
        model.addAttribute("scanned", true);
        model.addAttribute("tomcatCount", result.tomcats().size());
        model.addAttribute("alreadyAdded", suggestions.alreadyAdded());
        model.addAttribute("notes", result.notes());
        return results(model, suggestions.selection());
    }

    /** Saves the selected suggestions after checking all of them; nothing is saved while any selected row is invalid. */
    @PostMapping("/save")
    public String save(@PathVariable long hostId, @ModelAttribute("selection") Selection selection, Model model, RedirectAttributes flash) {
        ManagedHost host = manageableHost(hostId);
        List<Row> chosen = selection.getRows().stream().filter(Row::isSelected).toList();
        model.addAttribute("pageTitle", "Find Tomcat instances");
        model.addAttribute("host", host);
        model.addAttribute("scanned", true);
        if (chosen.isEmpty()) {
            model.addAttribute("formError", "Select at least one Tomcat instance to add.");
            return results(model, selection);
        }
        Map<Integer, String> errors = new LinkedHashMap<>();
        List<InstanceForm> forms = new ArrayList<>();
        Set<String> names = new HashSet<>(), targets = new HashSet<>();
        for (int i = 0; i < selection.getRows().size(); i++) {
            Row row = selection.getRows().get(i);
            if (!row.isSelected()) continue;
            InstanceForm form = toForm(host, row);
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(form, "form");
            if (form.getName() == null || form.getName().isBlank()) result.rejectValue("name", "required", "Enter a name.");
            if (DeploymentFilename.problem(form.getDeploymentFilename()) != null)
                result.rejectValue("deploymentFilename", "invalid", DeploymentFilename.problem(form.getDeploymentFilename()));
            instanceService.validate(form, result);
            // Selected rows must not clash with each other either.
            if (form.getName() != null && !names.add(form.getName().strip().toLowerCase(Locale.ROOT)))
                result.rejectValue("name", "duplicate", "Another selected row uses this name.");
            if (form.getDeploymentFilename() != null && DeploymentFilename.problem(form.getDeploymentFilename()) == null
                    && !targets.add(form.getCatalinaBase() + "|" + form.getAppBase() + "|" + DeploymentFilename.contextPath(form.getDeploymentFilename())))
                result.rejectValue("deploymentFilename", "duplicate", "Another selected row deploys the same application on this Tomcat.");
            if (result.hasErrors())
                errors.put(i, String.join(" ", new LinkedHashSet<>(result.getAllErrors().stream().map(e -> e.getDefaultMessage()).toList())));
            forms.add(form);
        }
        if (!errors.isEmpty()) {
            model.addAttribute("rowErrors", errors);
            model.addAttribute("formError", "Nothing was saved: fix the marked rows, or untick them.");
            return results(model, selection);
        }
        List<String> added = new ArrayList<>();
        for (InstanceForm form : forms) {
            InstanceService.InstanceChange change = instanceService.saveWithChanges(form);
            audit.instanceSaved(change);
            added.add(change.instance().getName());
        }
        flash.addFlashAttribute("success", "Added " + added.size() + " Tomcat instance" + (added.size() == 1 ? "" : "s") + ": " + String.join(", ", added)
                + ". Complete each one later: health check, restart and groups.");
        return "redirect:/infrastructure/hosts/" + host.getId();
    }

    private String results(Model model, Selection selection) {
        model.addAttribute("selection", selection);
        model.addAttribute("environments", Environment.values());
        return "infrastructure/discover";
    }

    /** The saved instance: required settings from the scan; health check off until a health URL is confirmed. */
    static InstanceForm toForm(ManagedHost host, Row row) {
        InstanceForm f = new InstanceForm();
        f.setHostId(host.getId());
        f.setName(row.getName() == null ? null : row.getName().strip());
        f.setCatalinaBase(row.getCatalinaBase());
        f.setAppBase(row.getAppBase() == null || row.getAppBase().isBlank() ? "webapps" : row.getAppBase());
        f.setDeploymentFilename(row.getDeploymentFilename() == null ? null : row.getDeploymentFilename().strip());
        f.setEnvironment(row.getEnvironment());
        f.setDeploymentMethod(TomcatInstance.DeploymentMethod.SSH_SFTP);
        f.setHttpPort(row.getHttpPort());
        f.setTomcatVersion(row.getTomcatVersion());
        f.setJavaVersion(row.getJavaVersion());
        f.setHealthCheckEnabled(false);
        f.setHealthUrl(row.getHealthUrl());
        f.setMonitoringEnabled(false);
        f.setBackupEnabled(true);
        f.setEnabled(true);
        return f;
    }

    record Suggestions(Selection selection, int alreadyAdded) { }

    /** One row per application not added yet (or one per Tomcat without applications); names unique on the host. */
    Suggestions suggest(ManagedHost host, TomcatScan.Result result) {
        List<TomcatInstance> existing = instances.findByHostIdOrderByNameAsc(host.getId());
        Set<String> takenNames = new HashSet<>();
        existing.forEach(i -> takenNames.add(i.getName().toLowerCase(Locale.ROOT)));
        Selection selection = new Selection();
        int alreadyAdded = 0;
        for (TomcatScan.Tomcat t : result.tomcats()) {
            List<TomcatInstance> here = existing.stream().filter(i -> t.deploymentDirectory().equals(i.getRemoteDeploymentDirectory())).toList();
            String details = details(t);
            String dirName = t.catalinaBase().substring(t.catalinaBase().lastIndexOf('/') + 1);
            List<TomcatScan.App> apps = t.apps();
            if (apps.isEmpty()) {
                if (!here.isEmpty()) { alreadyAdded++; continue; }
                selection.getRows().add(row(host, t, details, unique(dirName, takenNames), "", false, false));
                continue;
            }
            for (TomcatScan.App app : apps) {
                String context = DeploymentFilename.contextPath(app.filename());
                boolean added = here.stream().anyMatch(i -> app.filename().equalsIgnoreCase(i.getDeploymentFilename()) || context.equals(i.getDeploymentContextPath()));
                if (added) { alreadyAdded++; continue; }
                String appName = context.equals("/") ? "root" : context.substring(1).replace('/', '-');
                String name = apps.size() == 1 ? dirName : dirName + "-" + appName;
                selection.getRows().add(row(host, t, details, unique(name, takenNames), app.filename(), app.exploded(), true));
            }
        }
        return new Suggestions(selection, alreadyAdded);
    }

    private static Row row(ManagedHost host, TomcatScan.Tomcat t, String details, String name, String filename, boolean exploded, boolean selected) {
        Row r = new Row();
        r.setSelected(selected);
        r.setName(name);
        r.setGroup(t.catalinaBase());
        r.setDetails(details);
        r.setCatalinaBase(t.catalinaBase());
        // appBase as configured (relative to the base, or absolute when server.xml says so)
        r.setAppBase(t.appBase());
        r.setDeploymentFilename(filename);
        r.setExploded(exploded);
        r.setHttpPort(t.httpPort());
        r.setTomcatVersion(t.tomcatVersion());
        r.setJavaVersion(t.javaVersion());
        if (t.httpPort() != null) r.setHealthUrl("http://" + host.getHostname() + ":" + t.httpPort() + "{context}/");
        return r;
    }

    private static String details(TomcatScan.Tomcat t) {
        List<String> parts = new ArrayList<>();
        parts.add(t.tomcatVersion() == null ? "Tomcat (version unknown)" : "Tomcat " + t.tomcatVersion());
        if (t.javaVersion() != null) parts.add("Java " + t.javaVersion());
        parts.add(t.httpPort() == null ? "HTTP port unknown" : "HTTP " + t.httpPort());
        parts.add(t.running() ? "running" : "not running");
        if (t.systemdService() != null) parts.add("systemd " + t.systemdService());
        if (Boolean.FALSE.equals(t.autoDeploy())) parts.add("autoDeploy off");
        parts.add("deploys into " + t.deploymentDirectory());
        return String.join(" · ", parts);
    }

    private static String unique(String name, Set<String> taken) {
        String base = name.length() > 110 ? name.substring(0, 110) : name;
        String candidate = base;
        for (int i = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); i++) candidate = base + "-" + i;
        taken.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }
}
