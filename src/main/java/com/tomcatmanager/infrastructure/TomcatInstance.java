package com.tomcatmanager.infrastructure;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "tomcat_instances", uniqueConstraints = @UniqueConstraint(name = "uk_tomcat_host_name", columnNames = {"host_id", "name"}))
public class TomcatInstance {
    public enum DeploymentMethod { TOMCAT_MANAGER, SSH_SFTP }
    public enum ManagerProtocol { HTTP, HTTPS }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Size(max = 120) @Column(nullable = false, length = 120) private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "host_id", nullable = false) private ManagedHost host;
    @NotBlank @Size(max = 255) @Column(nullable = false, length = 255) private String catalinaBase;
    @NotBlank @Size(max = 255) @Column(nullable = false, length = 255) private String appBase = "webapps";
    /** Back up the currently deployed WAR before replacing it; when false, deployments here have no rollback point. */
    @Column(name = "backup_enabled", nullable = false) private boolean backupEnabled = true;
    /** Verify the application over HTTP(S) after each deployment; when false, health URL and port are optional. */
    @Column(name = "health_check_enabled", nullable = false) private boolean healthCheckEnabled = true;
    @Size(max = 255) @Column(length = 255) private String healthUrl;
    /** Health policy overrides; null uses the application defaults (app.deployment.*). */
    @Min(10) @Max(1800) @Column(name = "startup_timeout_seconds") private Integer startupTimeoutSeconds;
    @Min(1) @Max(10) @Column(name = "health_consecutive_successes") private Integer healthConsecutiveSuccesses;
    /** Accepted HTTP status codes, e.g. "200-299" or "200,204"; null accepts any 2xx. */
    @Size(max = 60) @Column(name = "health_expected_status", length = 60) private String healthExpectedStatus;
    /** Optional text the health response body must contain. */
    @Size(max = 200) @Column(name = "health_expected_text", length = 200) private String healthExpectedText;
    /** Include this instance in periodic application health monitoring. */
    @Column(name = "monitoring_enabled", nullable = false) private boolean monitoringEnabled = true;
    /** WAR filename releases are renamed to on this instance, e.g. appA.war (context /appA). */
    @Size(max = 255) @Column(name = "deployment_filename", length = 255) private String deploymentFilename;
    /** Known runtime, e.g. 10.1.30 and 21; used for release compatibility checks. Null when unknown. */
    @Size(max = 40) @Column(name = "tomcat_version", length = 40) private String tomcatVersion;
    @Column(name = "java_version") private Integer javaVersion;
    /** Null until assigned; deployments are blocked for instances without an environment. */
    @Enumerated(EnumType.STRING) @Column(length = 20) private Environment environment;
    @Min(1) @Max(65535) private Integer httpPort;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DeploymentMethod deploymentMethod = DeploymentMethod.SSH_SFTP;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ManagerProtocol managerProtocol = ManagerProtocol.HTTPS;
    @Size(max = 255) private String managerHost;
    @Min(1) @Max(65535) private int managerPort = 8443;
    @Size(max = 500) private String managerEndpoint = "/manager/text";
    @Size(max = 120) private String managerUsername;
    @Column(name = "manager_secret_encrypted", columnDefinition = "text") private String managerSecretEncrypted;
    @Min(1) @Max(300) @Column(nullable = false) private int timeoutSeconds = 15;
    @Min(0) @Max(5) @Column(nullable = false) private int maxRetries = 2;
    @Column(nullable = false) private boolean enabled = true;
    /** Optional full Tomcat restart around SSH/SFTP deployments (see TomcatRestart); NONE = hot deployment. */
    @Enumerated(EnumType.STRING) @Column(name = "restart_mode", nullable = false, length = 10) private TomcatRestart.Mode restartMode = TomcatRestart.Mode.NONE;
    @Size(max = 100) @Column(name = "restart_service", length = 100) private String restartService;
    @Column(name = "restart_use_sudo", nullable = false) private boolean restartUseSudo;
    /** CATALINA_HOME for the Tomcat scripts; empty = the CATALINA_BASE. */
    @Size(max = 255) @Column(name = "catalina_home", length = 255) private String catalinaHome;
    @Min(0) @Max(300) @Column(name = "restart_stop_wait_seconds") private Integer restartStopWaitSeconds;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "server_group_instances", joinColumns = @JoinColumn(name = "instance_id"), inverseJoinColumns = @JoinColumn(name = "group_id"))
    private Set<ServerGroup> groups = new LinkedHashSet<>();
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId(){return id;} public String getName(){return name;} public void setName(String v){name=v==null?null:v.trim();}
    public ManagedHost getHost(){return host;} public void setHost(ManagedHost v){host=v;}
    public String getCatalinaBase(){return catalinaBase;} public void setCatalinaBase(String v){catalinaBase=v==null?null:v.trim();}
    public String getAppBase(){return appBase;} public void setAppBase(String v){appBase=v==null?null:v.trim();}
    public boolean isBackupEnabled(){return backupEnabled;} public void setBackupEnabled(boolean v){backupEnabled=v;}
    public TomcatRestart.Mode getRestartMode(){return restartMode;} public void setRestartMode(TomcatRestart.Mode v){restartMode = v == null ? TomcatRestart.Mode.NONE : v;}
    public String getRestartService(){return restartService;} public void setRestartService(String v){restartService = v == null || v.isBlank() ? null : v.strip();}
    public boolean isRestartUseSudo(){return restartUseSudo;} public void setRestartUseSudo(boolean v){restartUseSudo=v;}
    public String getCatalinaHome(){return catalinaHome;} public void setCatalinaHome(String v){catalinaHome = v == null || v.isBlank() ? null : v.strip();}
    public Integer getRestartStopWaitSeconds(){return restartStopWaitSeconds;} public void setRestartStopWaitSeconds(Integer v){restartStopWaitSeconds=v;}
    /** Tomcat is stopped and started around each deployment (SSH/SFTP only). */
    public boolean restartsTomcat(){return restartMode != null && restartMode != TomcatRestart.Mode.NONE && deploymentMethod == DeploymentMethod.SSH_SFTP;}
    public boolean isHealthCheckEnabled(){return healthCheckEnabled;} public void setHealthCheckEnabled(boolean v){healthCheckEnabled=v;}
    public String getHealthUrl(){return healthUrl;} public void setHealthUrl(String v){healthUrl=v==null||v.isBlank()?null:v.trim();}
    public Integer getStartupTimeoutSeconds(){return startupTimeoutSeconds;} public void setStartupTimeoutSeconds(Integer v){startupTimeoutSeconds=v;}
    public Integer getHealthConsecutiveSuccesses(){return healthConsecutiveSuccesses;} public void setHealthConsecutiveSuccesses(Integer v){healthConsecutiveSuccesses=v;}
    public String getHealthExpectedStatus(){return healthExpectedStatus;} public void setHealthExpectedStatus(String v){healthExpectedStatus=v==null||v.isBlank()?null:v.replace(" ", "");}
    public String getHealthExpectedText(){return healthExpectedText;} public void setHealthExpectedText(String v){healthExpectedText=v==null||v.isBlank()?null:v.strip();}
    public boolean isMonitoringEnabled(){return monitoringEnabled;} public void setMonitoringEnabled(boolean v){monitoringEnabled=v;}
    public String getDeploymentFilename(){return deploymentFilename;} public void setDeploymentFilename(String v){deploymentFilename=v==null||v.isBlank()?null:v.trim();}
    public String getDeploymentContextPath(){return deploymentFilename==null?null:DeploymentFilename.contextPath(deploymentFilename);}
    public String getTomcatVersion(){return tomcatVersion;} public void setTomcatVersion(String v){tomcatVersion=v==null||v.isBlank()?null:v.trim();}
    public Integer getJavaVersion(){return javaVersion;} public void setJavaVersion(Integer v){javaVersion=v;}
    public Environment getEnvironment(){return environment;} public void setEnvironment(Environment v){environment=v;}
    public Integer getHttpPort(){return httpPort;} public void setHttpPort(Integer v){httpPort=v;}
    public DeploymentMethod getDeploymentMethod(){return deploymentMethod;} public void setDeploymentMethod(DeploymentMethod v){deploymentMethod=v;}
    public ManagerProtocol getManagerProtocol(){return managerProtocol;} public void setManagerProtocol(ManagerProtocol v){managerProtocol=v;}
    public String getManagerHost(){return managerHost;} public void setManagerHost(String v){managerHost=v;}
    public int getManagerPort(){return managerPort;} public void setManagerPort(int v){managerPort=v;}
    public String getManagerEndpoint(){return managerEndpoint;} public void setManagerEndpoint(String v){managerEndpoint=v;}
    public String getManagerUsername(){return managerUsername;} public void setManagerUsername(String v){managerUsername=v;}
    public String getManagerSecretEncrypted(){return managerSecretEncrypted;} public void setManagerSecretEncrypted(String v){managerSecretEncrypted=v;}
    public int getTimeoutSeconds(){return timeoutSeconds;} public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public int getMaxRetries(){return maxRetries;} public void setMaxRetries(int v){maxRetries=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public Set<ServerGroup> getGroups(){return groups;} public void setGroups(Set<ServerGroup> v){groups=v==null?new LinkedHashSet<>():v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}

    /** Directory WAR files are placed in: {@code appBase} when absolute, otherwise {@code CATALINA_BASE/appBase}. */
    public String getRemoteDeploymentDirectory() {
        if (appBase == null || catalinaBase == null) return null;
        if (appBase.startsWith("/")) return appBase;
        return (catalinaBase.endsWith("/") ? catalinaBase : catalinaBase + "/") + appBase;
    }
    public boolean hasManagerSecret() { return managerSecretEncrypted != null; }

    /** Health URL for a deployed context: replaces {context} with e.g. "/appA" (or "" for ROOT). */
    public String healthUrlFor(String contextPath) {
        if (healthUrl == null) return null;
        return healthUrl.replace("{context}", "/".equals(contextPath) ? "" : contextPath);
    }
}
