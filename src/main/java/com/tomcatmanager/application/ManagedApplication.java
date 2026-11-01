package com.tomcatmanager.application;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** A logical deployable system. Releases belong to it; its compatibility requirements apply to all of them. */
@Entity
@Table(name = "applications")
public class ManagedApplication {
    /** Which Servlet API the application is built against; DETECT uses what the WAR inspection found. */
    public enum ServletApi { DETECT, JAVAX, JAKARTA }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(name = "servlet_api", nullable = false, length = 20) private ServletApi servletApi = ServletApi.DETECT;
    @Column(name = "min_java_version") private Integer minJavaVersion;
    @Column(name = "max_java_version") private Integer maxJavaVersion;
    /** Comma-separated Tomcat lines such as {@code 10.1,11.0}; empty means "any". */
    @Column(name = "supported_tomcat_versions", length = 200) private String supportedTomcatVersions;
    @Column(name = "other_requirements", length = 2000) private String otherRequirements;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false, updatable = false, length = 120) private String createdBy;
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    @Column(nullable = false, length = 120) private String updatedBy;
    @Version private long version;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? null : v.trim(); }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public ServletApi getServletApi() { return servletApi; }
    public void setServletApi(ServletApi v) { servletApi = v; }
    public Integer getMinJavaVersion() { return minJavaVersion; }
    public void setMinJavaVersion(Integer v) { minJavaVersion = v; }
    public Integer getMaxJavaVersion() { return maxJavaVersion; }
    public void setMaxJavaVersion(Integer v) { maxJavaVersion = v; }
    public String getSupportedTomcatVersions() { return supportedTomcatVersions; }
    public void setSupportedTomcatVersions(String v) { supportedTomcatVersions = v; }
    public String getOtherRequirements() { return otherRequirements; }
    public void setOtherRequirements(String v) { otherRequirements = v; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }

    public void markCreated(String actor) { createdBy = actor; markUpdated(actor); }
    public void markUpdated(String actor) { updatedBy = actor; updatedAt = Instant.now(); }

    public List<String> getSupportedTomcatList() {
        if (supportedTomcatVersions == null || supportedTomcatVersions.isBlank()) return List.of();
        return Arrays.stream(supportedTomcatVersions.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
