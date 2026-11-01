package com.tomcatmanager.settings;

import jakarta.persistence.*;
import java.time.Instant;

/** One runtime-adjustable, non-secret setting. */
@Entity
@Table(name = "app_settings")
public class AppSetting {
    @Id @Column(name = "setting_key", length = 80) private String key;
    @Column(name = "setting_value", nullable = false, length = 1000) private String value;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Column(name = "updated_by", nullable = false, length = 60) private String updatedBy;

    protected AppSetting() { }

    AppSetting(String key, String value, String updatedBy) { this.key = key; this.value = value; this.updatedBy = updatedBy; }

    void update(String value, String actor) { this.value = value; this.updatedBy = actor; this.updatedAt = Instant.now(); }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
