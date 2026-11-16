package com.tomcatmanager.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Tomcat instance create/edit form. SSH credentials and host key come from the selected host. */
public class InstanceForm {
    private Long id;
    @NotBlank @Size(max = 120) private String name;
    @NotNull private Long hostId;
    @NotBlank @Size(max = 255) private String catalinaBase;
    @NotBlank @Size(max = 255) private String appBase = "webapps";
    private boolean backupEnabled = true;
    private boolean healthCheckEnabled = true;
    @Size(max = 255) private String healthUrl;
    @Min(value = 10, message = "At least 10 seconds.") @Max(value = 1800, message = "At most 1800 seconds (30 minutes).") private Integer startupTimeoutSeconds;
    @Min(value = 1, message = "At least 1.") @Max(value = 10, message = "At most 10.") private Integer healthConsecutiveSuccesses;
    @Size(max = 60) private String healthExpectedStatus;
    @Size(max = 200) private String healthExpectedText;
    private boolean monitoringEnabled = true;
    @Size(max = 255) private String deploymentFilename;
    private Environment environment;
    @Size(max = 40) private String tomcatVersion;
    @jakarta.validation.constraints.Min(value = 8, message = "Java 8 or newer.") @jakarta.validation.constraints.Max(40) private Integer javaVersion;
    @Min(1) @Max(65535) private Integer httpPort = 8080;
    @NotNull private TomcatInstance.DeploymentMethod deploymentMethod = TomcatInstance.DeploymentMethod.SSH_SFTP;
    /** e.g. https://tomcat01.example.com:8443/manager/text; stored as protocol, host, port and path. */
    @Size(max = 500) private String managerUrl;
    @Size(max = 120) private String managerUsername;
    @Size(max = 1024) private String managerSecret;
    private boolean clearManagerSecret;
    @Min(1) @Max(300) private int timeoutSeconds = 15;
    @Min(0) @Max(5) private int maxRetries = 2;
    private boolean enabled = true;
    @NotNull private TomcatRestart.Mode restartMode = TomcatRestart.Mode.NONE;
    @Size(max = 100) private String restartService;
    private boolean restartUseSudo;
    @Size(max = 255) private String catalinaHome;
    @Min(value = 0, message = "0 or more seconds.") @Max(value = 300, message = "At most 300 seconds.") private Integer restartStopWaitSeconds;
    private List<Long> groupIds = List.of();
    private boolean managerSecretStored;

    public static InstanceForm from(TomcatInstance i) {
        InstanceForm f = new InstanceForm();
        f.id = i.getId(); f.name = i.getName(); f.hostId = i.getHost().getId(); f.catalinaBase = i.getCatalinaBase(); f.appBase = i.getAppBase();
        f.environment = i.getEnvironment();         f.tomcatVersion = i.getTomcatVersion(); f.javaVersion = i.getJavaVersion();
        f.deploymentFilename = i.getDeploymentFilename(); f.backupEnabled = i.isBackupEnabled(); f.healthCheckEnabled = i.isHealthCheckEnabled(); f.healthUrl = i.getHealthUrl();
        f.startupTimeoutSeconds = i.getStartupTimeoutSeconds(); f.healthConsecutiveSuccesses = i.getHealthConsecutiveSuccesses();
        f.healthExpectedStatus = i.getHealthExpectedStatus(); f.healthExpectedText = i.getHealthExpectedText(); f.monitoringEnabled = i.isMonitoringEnabled();
        f.httpPort = i.getHttpPort(); f.deploymentMethod = i.getDeploymentMethod();
        boolean managerConfigured = i.getDeploymentMethod() == TomcatInstance.DeploymentMethod.TOMCAT_MANAGER || i.getManagerUsername() != null;
        f.managerUrl = managerConfigured ? ManagerUrl.of(i).toString() : null;
        f.managerUsername = i.getManagerUsername();
        f.timeoutSeconds = i.getTimeoutSeconds(); f.maxRetries = i.getMaxRetries(); f.enabled = i.isEnabled();
        f.restartMode = i.getRestartMode(); f.restartService = i.getRestartService(); f.restartUseSudo = i.isRestartUseSudo();
        f.catalinaHome = i.getCatalinaHome(); f.restartStopWaitSeconds = i.getRestartStopWaitSeconds();
        f.groupIds = i.getGroups().stream().map(ServerGroup::getId).toList();
        f.managerSecretStored = i.hasManagerSecret();
        return f;
    }

    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public Long getHostId(){return hostId;} public void setHostId(Long v){hostId=v;}
    public String getCatalinaBase(){return catalinaBase;} public void setCatalinaBase(String v){catalinaBase=v;}
    public String getAppBase(){return appBase;} public void setAppBase(String v){appBase=v;}
    public boolean isBackupEnabled(){return backupEnabled;} public void setBackupEnabled(boolean v){backupEnabled=v;}
    public boolean isHealthCheckEnabled(){return healthCheckEnabled;} public void setHealthCheckEnabled(boolean v){healthCheckEnabled=v;}
    public String getHealthUrl(){return healthUrl;} public void setHealthUrl(String v){healthUrl=v;}
    public Integer getStartupTimeoutSeconds(){return startupTimeoutSeconds;} public void setStartupTimeoutSeconds(Integer v){startupTimeoutSeconds=v;}
    public Integer getHealthConsecutiveSuccesses(){return healthConsecutiveSuccesses;} public void setHealthConsecutiveSuccesses(Integer v){healthConsecutiveSuccesses=v;}
    public String getHealthExpectedStatus(){return healthExpectedStatus;} public void setHealthExpectedStatus(String v){healthExpectedStatus=v;}
    public String getHealthExpectedText(){return healthExpectedText;} public void setHealthExpectedText(String v){healthExpectedText=v;}
    public boolean isMonitoringEnabled(){return monitoringEnabled;} public void setMonitoringEnabled(boolean v){monitoringEnabled=v;}
    public String getDeploymentFilename(){return deploymentFilename;} public void setDeploymentFilename(String v){deploymentFilename=v;}
    public Environment getEnvironment(){return environment;} public void setEnvironment(Environment v){environment=v;}
    public String getTomcatVersion(){return tomcatVersion;} public void setTomcatVersion(String v){tomcatVersion=v;}
    public Integer getJavaVersion(){return javaVersion;} public void setJavaVersion(Integer v){javaVersion=v;}
    public Integer getHttpPort(){return httpPort;} public void setHttpPort(Integer v){httpPort=v;}
    public TomcatInstance.DeploymentMethod getDeploymentMethod(){return deploymentMethod;} public void setDeploymentMethod(TomcatInstance.DeploymentMethod v){deploymentMethod=v;}
    public String getManagerUrl(){return managerUrl;} public void setManagerUrl(String v){managerUrl=v;}
    public String getManagerUsername(){return managerUsername;} public void setManagerUsername(String v){managerUsername=v;}
    public String getManagerSecret(){return managerSecret;} public void setManagerSecret(String v){managerSecret=v;}
    public boolean isClearManagerSecret(){return clearManagerSecret;} public void setClearManagerSecret(boolean v){clearManagerSecret=v;}
    public int getTimeoutSeconds(){return timeoutSeconds;} public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public int getMaxRetries(){return maxRetries;} public void setMaxRetries(int v){maxRetries=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public TomcatRestart.Mode getRestartMode(){return restartMode;} public void setRestartMode(TomcatRestart.Mode v){restartMode=v;}
    public String getRestartService(){return restartService;} public void setRestartService(String v){restartService=v;}
    public boolean isRestartUseSudo(){return restartUseSudo;} public void setRestartUseSudo(boolean v){restartUseSudo=v;}
    public String getCatalinaHome(){return catalinaHome;} public void setCatalinaHome(String v){catalinaHome=v;}
    public Integer getRestartStopWaitSeconds(){return restartStopWaitSeconds;} public void setRestartStopWaitSeconds(Integer v){restartStopWaitSeconds=v;}
    public List<Long> getGroupIds(){return groupIds;} public void setGroupIds(List<Long> v){groupIds=v;}
    public boolean isManagerSecretStored(){return managerSecretStored;}
    void setManagerSecretStored(boolean v){managerSecretStored=v;}
}
