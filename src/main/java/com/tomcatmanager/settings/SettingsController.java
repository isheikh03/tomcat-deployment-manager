package com.tomcatmanager.settings;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.connectivity.ConnectivityProperties;
import com.tomcatmanager.deployment.DeploymentProperties;
import com.tomcatmanager.health.HealthProperties;
import com.tomcatmanager.notifications.NotificationService;
import com.tomcatmanager.release.ReleaseProperties;
import com.tomcatmanager.security.CredentialRotationService;
import com.tomcatmanager.security.SecretEncryptionService;
import com.tomcatmanager.users.SecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application settings (administrators only via /admin/**): runtime policies, notification email,
 * encryption-key status and credential re-encryption, and the effective (non-secret) configuration.
 */
@Controller
public class SettingsController {
    private final SettingsService settings;
    private final NotificationService notifications;
    private final SecretEncryptionService encryption;
    private final CredentialRotationService rotation;
    private final AuditService audit;
    private final DeploymentProperties deployment;
    private final HealthProperties health;
    private final ConnectivityProperties connectivity;
    private final ReleaseProperties release;
    private final SecurityProperties security;
    private final String mailHost;
    private final String sessionTimeout;
    private final boolean secureCookie;

    public SettingsController(SettingsService settings, NotificationService notifications, SecretEncryptionService encryption,
                              CredentialRotationService rotation, AuditService audit, DeploymentProperties deployment, HealthProperties health,
                              ConnectivityProperties connectivity, ReleaseProperties release, SecurityProperties security,
                              @Value("${spring.mail.host:}") String mailHost, @Value("${server.servlet.session.timeout:30m}") String sessionTimeout,
                              @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.settings = settings; this.notifications = notifications; this.encryption = encryption; this.rotation = rotation; this.audit = audit;
        this.deployment = deployment; this.health = health; this.connectivity = connectivity; this.release = release; this.security = security;
        this.mailHost = mailHost; this.sessionTimeout = sessionTimeout; this.secureCookie = secureCookie;
    }

    @GetMapping("/admin/settings")
    public String index(Model model) {
        model.addAttribute("pageTitle", "Settings");
        Map<String, SettingsService.Entry> entries = settings.entries();
        model.addAttribute("entries", entries);
        model.addAttribute("overriddenCount", entries.values().stream().filter(SettingsService.Entry::overridden).count());
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        model.addAttribute("keyId", encryption.keyId());
        model.addAttribute("previousKeyId", encryption.previousKeyId());
        model.addAttribute("rotation", encryption.isConfigured() ? rotation.status() : null);
        model.addAttribute("mailConfigured", mailHost != null && !mailHost.isBlank());
        model.addAttribute("effective", effective());
        return "admin/settings";
    }

    /** Non-secret configuration currently in effect (environment / application.properties). */
    private Map<String, Map<String, String>> effective() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        Map<String, String> storage = new LinkedHashMap<>();
        storage.put("Release storage (ARTIFACT_STORAGE)", release.storageDir().toAbsolutePath().normalize().toString());
        storage.put("Backup directory (DEPLOYMENT_BACKUP_DIR)", deployment.backupDir().toAbsolutePath().normalize().toString());
        storage.put("Maximum upload size", release.maxUploadSize().toMegabytes() + " MB");
        groups.put("Storage", storage);
        Map<String, String> deploy = new LinkedHashMap<>();
        deploy.put("Default startup timeout", deployment.startupTimeoutSeconds() + " s");
        deploy.put("Default consecutive healthy responses", String.valueOf(deployment.consecutiveSuccesses()));
        deploy.put("Health poll interval / request timeout", deployment.healthInterval().toSeconds() + " s / " + deployment.healthRequestTimeout().toSeconds() + " s");
        deploy.put("Transfer timeout", deployment.transferTimeout().toMinutes() + " min");
        deploy.put("Concurrent deployments / parallel targets", deployment.maxConcurrentDeployments() + " / " + deployment.maxParallelTargets());
        groups.put("Deployments", deploy);
        Map<String, String> monitoring = new LinkedHashMap<>();
        monitoring.put("Check interval", health.monitorInterval().toMinutes() + " min");
        monitoring.put("Request timeout / parallel checks", health.requestTimeout().toSeconds() + " s / " + health.maxConcurrentChecks());
        monitoring.put("Unhealthy after", health.unhealthyAfterFailures() + " consecutive failures");
        monitoring.put("History kept", health.retention().toDays() + " days");
        groups.put("Health monitoring", monitoring);
        Map<String, String> network = new LinkedHashMap<>();
        network.put("Loopback targets allowed", String.valueOf(connectivity.targetPolicy().allowLoopback()));
        network.put("Link-local targets allowed", String.valueOf(connectivity.targetPolicy().allowLinkLocal()));
        network.put("Private-network targets allowed", String.valueOf(connectivity.targetPolicy().allowPrivate()));
        network.put("TLS trust store", connectivity.tls().trustStore() == null || connectivity.tls().trustStore().isBlank() ? "JVM default" : connectivity.tls().trustStore());
        network.put("Connection results stale after", connectivity.resultStaleAfter().toHours() + " h");
        groups.put("Network", network);
        Map<String, String> sec = new LinkedHashMap<>();
        sec.put("Session timeout", sessionTimeout);
        sec.put("Secure session cookie (HTTPS)", String.valueOf(secureCookie));
        sec.put("Lockout", security.maxFailedLogins() + " failed sign-ins → " + security.lockoutDuration().toMinutes() + " min");
        sec.put("Minimum password length", String.valueOf(Math.max(8, security.minPasswordLength())));
        sec.put("Development accounts (APP_DEV_USERS)", security.devUsers() ? "ENABLED – development only" : "off");
        sec.put("SMTP server", mailHost == null || mailHost.isBlank() ? "not configured" : mailHost);
        groups.put("Security", sec);
        return groups;
    }

    @PostMapping("/admin/settings")
    public String save(@RequestParam(defaultValue = "false") boolean operatorUpload, @RequestParam(defaultValue = "false") boolean healthMonitoring,
                       @RequestParam String backupRetention, @RequestParam(defaultValue = "false") boolean emailEnabled,
                       @RequestParam(defaultValue = "") String emailRecipients, @RequestParam(defaultValue = "WARNING") String emailMinLevel,
                       Principal principal, RedirectAttributes flash) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SettingsService.OPERATOR_UPLOAD, String.valueOf(operatorUpload));
        values.put(SettingsService.HEALTH_MONITORING, String.valueOf(healthMonitoring));
        values.put(SettingsService.BACKUP_RETENTION, backupRetention);
        values.put(SettingsService.EMAIL_ENABLED, String.valueOf(emailEnabled));
        values.put(SettingsService.EMAIL_RECIPIENTS, emailRecipients);
        values.put(SettingsService.EMAIL_MIN_LEVEL, emailMinLevel);
        try {
            int changed = settings.update(values, principal.getName());
            flash.addFlashAttribute("success", changed == 0 ? "No settings changed." : changed + " setting(s) saved. They apply immediately.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "Not saved: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/test-email")
    public String testEmail(Principal principal, RedirectAttributes flash) {
        String error = notifications.sendTestEmail(principal.getName());
        audit.record(principal.getName(), "TEST_EMAIL", "SETTING", SettingsService.EMAIL_RECIPIENTS,
                error == null ? com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS : com.tomcatmanager.audit.AuditEvent.Outcome.FAILURE,
                error == null ? "Sent a test notification email." : "Test notification email failed: " + error);
        if (error == null) flash.addFlashAttribute("success", "Test email sent to " + String.join(", ", settings.emailRecipients()) + ".");
        else flash.addFlashAttribute("error", "Test email failed: " + error);
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/settings/reencrypt")
    public String reencrypt(Principal principal, RedirectAttributes flash) {
        try {
            int n = rotation.reencryptAll(principal.getName());
            flash.addFlashAttribute("success", n == 0 ? "All stored credentials already use the current key."
                    : n + " credential(s) re-encrypted with the current key. You can now remove APP_ENCRYPTION_KEY_PREVIOUS.");
        } catch (IllegalStateException e) {
            audit.failure("CREDENTIALS_REENCRYPTED", "SETTING", "encryption-key", "Re-encryption refused: " + e.getMessage());
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
