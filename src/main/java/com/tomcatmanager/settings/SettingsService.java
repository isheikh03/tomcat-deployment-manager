package com.tomcatmanager.settings;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.deployment.DeploymentProperties;
import com.tomcatmanager.health.HealthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Runtime policies an administrator can change without a restart. Values not set in the database
 * fall back to the application configuration. Every change is audited with old and new value
 * (settings are never secret).
 */
@Service
public class SettingsService {
    public static final String OPERATOR_UPLOAD = "release.operator-upload-enabled";
    public static final String HEALTH_MONITORING = "health.monitoring-enabled";
    public static final String BACKUP_RETENTION = "backup.retention-per-context";
    public static final String EMAIL_ENABLED = "notifications.email-enabled";
    public static final String EMAIL_RECIPIENTS = "notifications.email-recipients";
    public static final String EMAIL_MIN_LEVEL = "notifications.email-min-level";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]{1,64}@[^@\\s,;]{1,190}\\.[^@\\s,;]{2,}$");
    private static final List<String> LEVELS = List.of("INFO", "SUCCESS", "WARNING", "DANGER");

    private final AppSettingRepository repository;
    private final AuditService audit;
    private final Map<String, String> defaults = new LinkedHashMap<>();
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public SettingsService(AppSettingRepository repository, AuditService audit, DeploymentProperties deployment, HealthProperties health,
                           @Value("${app.release.operator-upload-enabled:true}") boolean operatorUpload) {
        this.repository = repository; this.audit = audit;
        defaults.put(OPERATOR_UPLOAD, String.valueOf(operatorUpload));
        defaults.put(HEALTH_MONITORING, String.valueOf(health.monitoringEnabled()));
        defaults.put(BACKUP_RETENTION, String.valueOf(deployment.backupRetentionPerContext()));
        defaults.put(EMAIL_ENABLED, "false");
        defaults.put(EMAIL_RECIPIENTS, "");
        defaults.put(EMAIL_MIN_LEVEL, "WARNING");
    }

    public boolean operatorUploadEnabled() { return Boolean.parseBoolean(get(OPERATOR_UPLOAD)); }
    public boolean healthMonitoringEnabled() { return Boolean.parseBoolean(get(HEALTH_MONITORING)); }
    public int backupRetentionPerContext() { return Integer.parseInt(get(BACKUP_RETENTION)); }
    public boolean emailEnabled() { return Boolean.parseBoolean(get(EMAIL_ENABLED)); }
    public List<String> emailRecipients() {
        return Arrays.stream(get(EMAIL_RECIPIENTS).split("[,;\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
    public String emailMinLevel() { return get(EMAIL_MIN_LEVEL); }

    public String get(String key) {
        Optional<String> stored = cache.computeIfAbsent(key, k -> repository.findById(k).map(AppSetting::getValue));
        return stored.orElse(defaults.get(key));
    }

    /** Forgets cached values (e.g. after the table was changed outside the application). */
    public void refresh() { cache.clear(); }

    /** Current value, whether it is overridden in the database, and by whom. */
    public record Entry(String key, String value, String defaultValue, boolean overridden, String updatedBy, Instant updatedAt) { }

    public Map<String, Entry> entries() {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (String key : defaults.keySet()) {
            Optional<AppSetting> s = repository.findById(key);
            result.put(key, new Entry(key, s.map(AppSetting::getValue).orElse(defaults.get(key)), defaults.get(key), s.isPresent(),
                    s.map(AppSetting::getUpdatedBy).orElse(null), s.map(AppSetting::getUpdatedAt).orElse(null)));
        }
        return result;
    }

    /** Validates and stores changed values; returns the number of changes. */
    public int update(Map<String, String> values, String actor) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!defaults.containsKey(e.getKey())) throw new IllegalArgumentException("Unknown setting " + e.getKey() + ".");
            normalized.put(e.getKey(), normalize(e.getKey(), e.getValue()));
        }
        int changed = 0;
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            String old = get(e.getKey());
            if (old.equals(e.getValue())) continue;
            AppSetting setting = repository.findById(e.getKey()).orElse(null);
            if (setting == null) setting = new AppSetting(e.getKey(), e.getValue(), actor);
            else setting.update(e.getValue(), actor);
            repository.save(setting);
            cache.remove(e.getKey());
            changed++;
            audit.success("SETTING_CHANGED", "SETTING", e.getKey(), "Changed " + e.getKey() + " from \"" + old + "\" to \"" + e.getValue() + "\".");
        }
        return changed;
    }

    private static String normalize(String key, String raw) {
        String v = raw == null ? "" : raw.strip();
        return switch (key) {
            case OPERATOR_UPLOAD, HEALTH_MONITORING, EMAIL_ENABLED -> {
                if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(key + " must be true or false.");
                yield v;
            }
            case BACKUP_RETENTION -> {
                int n;
                try { n = Integer.parseInt(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("Backup retention must be a number."); }
                if (n < 0 || n > 1000) throw new IllegalArgumentException("Backup retention must be between 0 (keep all) and 1000.");
                yield String.valueOf(n);
            }
            case EMAIL_RECIPIENTS -> {
                List<String> list = Arrays.stream(v.split("[,;\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
                if (list.size() > 50) throw new IllegalArgumentException("At most 50 email recipients.");
                for (String address : list) if (!EMAIL.matcher(address).matches()) throw new IllegalArgumentException("Not a valid email address: " + address);
                yield String.join(", ", list);
            }
            case EMAIL_MIN_LEVEL -> {
                String upper = v.toUpperCase();
                if (!LEVELS.contains(upper)) throw new IllegalArgumentException("Minimum level must be one of " + LEVELS + ".");
                yield upper;
            }
            default -> v;
        };
    }
}
