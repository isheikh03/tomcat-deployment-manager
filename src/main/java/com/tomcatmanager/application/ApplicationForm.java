package com.tomcatmanager.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class ApplicationForm {
    /** Tomcat release lines offered as checkboxes. */
    public static final List<String> TOMCAT_LINES = List.of("8.5", "9.0", "10.0", "10.1", "11.0");

    private Long id;
    private long version;
    /** Team of a new application; an application stays in its team (it deploys only to that team's instances). */
    private Long teamId;
    @NotBlank(message = "Enter an application name.") @Size(max = 120) private String name;
    @Size(max = 2000) private String description;
    @NotNull private ManagedApplication.ServletApi servletApi = ManagedApplication.ServletApi.DETECT;
    @Min(8) @Max(40) private Integer minJavaVersion;
    @Min(8) @Max(40) private Integer maxJavaVersion;
    private List<String> supportedTomcatVersions = new ArrayList<>();
    @Size(max = 2000) private String otherRequirements;

    public static ApplicationForm from(ManagedApplication a) {
        ApplicationForm f = new ApplicationForm();
        f.teamId = a.getTeamId();
        f.id = a.getId(); f.version = a.getVersion(); f.name = a.getName(); f.description = a.getDescription();
        f.servletApi = a.getServletApi(); f.minJavaVersion = a.getMinJavaVersion(); f.maxJavaVersion = a.getMaxJavaVersion();
        f.supportedTomcatVersions = new ArrayList<>(a.getSupportedTomcatList()); f.otherRequirements = a.getOtherRequirements();
        return f;
    }

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTeamId() { return teamId; } public void setTeamId(Long v) { teamId = v; }
    public long getVersion() { return version; } public void setVersion(long v) { version = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public ManagedApplication.ServletApi getServletApi() { return servletApi; } public void setServletApi(ManagedApplication.ServletApi v) { servletApi = v; }
    public Integer getMinJavaVersion() { return minJavaVersion; } public void setMinJavaVersion(Integer v) { minJavaVersion = v; }
    public Integer getMaxJavaVersion() { return maxJavaVersion; } public void setMaxJavaVersion(Integer v) { maxJavaVersion = v; }
    public List<String> getSupportedTomcatVersions() { return supportedTomcatVersions; }
    public void setSupportedTomcatVersions(List<String> v) { supportedTomcatVersions = v == null ? new ArrayList<>() : v; }
    public String getOtherRequirements() { return otherRequirements; } public void setOtherRequirements(String v) { otherRequirements = v; }
}
