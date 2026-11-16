package com.tomcatmanager.infrastructure;

import com.tomcatmanager.security.SecretEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentFilenameTest {

    @Test void contextPathFollowsTomcatNaming() {
        assertThat(DeploymentFilename.contextPath("app.war")).isEqualTo("/app");
        assertThat(DeploymentFilename.contextPath("ROOT.war")).isEqualTo("/");
        assertThat(DeploymentFilename.contextPath("shop#admin.war")).isEqualTo("/shop/admin");
        assertThat(DeploymentFilename.contextPath("app##2.0.war")).isEqualTo("/app");
        assertThat(DeploymentFilename.version("app##2.0.war")).isEqualTo("2.0");
        assertThat(DeploymentFilename.version("app.war")).isNull();
    }

    @Test void acceptsValidNames() {
        for (String ok : List.of("app.war", "ROOT.war", "my-app_1.2.war", "shop#admin.war", "app##20261028.war")) {
            assertThat(DeploymentFilename.problem(ok)).as(ok).isNull();
        }
    }

    @Test void rejectsUnsafeOrInvalidNames() {
        assertThat(DeploymentFilename.problem(null)).contains("Enter");
        assertThat(DeploymentFilename.problem("../app.war")).contains("without a directory");
        assertThat(DeploymentFilename.problem("C:\\x\\app.war")).contains("without a directory");
        assertThat(DeploymentFilename.problem("app.zip")).contains(".war");
        assertThat(DeploymentFilename.problem("app A.war")).isNotNull();
        assertThat(DeploymentFilename.problem(".hidden.war")).isNotNull();
        assertThat(DeploymentFilename.problem("app;rm.war")).isNotNull();
        assertThat(DeploymentFilename.problem("#app.war")).isNotNull();
        assertThat(DeploymentFilename.problem("app#.war")).isNotNull();
        assertThat(DeploymentFilename.problem("app##.war")).contains("version");
    }

    @Test void twoInstancesCannotDeploySameContextIntoSameDirectory() {
        ManagedHost host = new ManagedHost(); host.setId(1L); host.setHostname("10.0.0.5");
        TomcatInstance existing = new TomcatInstance() { @Override public Long getId() { return 9L; } };
        existing.setName("tomcat-a"); existing.setHost(host); existing.setCatalinaBase("/opt/tomcat/a"); existing.setAppBase("webapps");
        existing.setDeploymentFilename("app.war");

        TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
        ManagedHostRepository hosts = mock(ManagedHostRepository.class);
        when(instances.findByHostIdOrderByNameAsc(1L)).thenReturn(List.of(existing));
        when(hosts.findById(1L)).thenReturn(Optional.of(host));
        InstanceService service = new InstanceService(instances, hosts, mock(ServerGroupRepository.class), new SecretEncryptionService(""));

        InstanceForm clash = form("tomcat-a2", "/opt/tomcat/a", "APP.war");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(clash, "form");
        service.validate(clash, errors);
        assertThat(errors.getFieldError("deploymentFilename").getDefaultMessage()).contains("tomcat-a").contains("/app");

        InstanceForm sameContextOtherVersion = form("tomcat-a3", "/opt/tomcat/a", "app##2.war");
        errors = new BeanPropertyBindingResult(sameContextOtherVersion, "form");
        service.validate(sameContextOtherVersion, errors);
        assertThat(errors.getFieldError("deploymentFilename")).isNotNull();

        InstanceForm otherTomcat = form("tomcat-b", "/opt/tomcat/b", "app.war");
        errors = new BeanPropertyBindingResult(otherTomcat, "form");
        service.validate(otherTomcat, errors);
        assertThat(errors.getFieldError("deploymentFilename")).isNull();
    }

    private static InstanceForm form(String name, String catalinaBase, String filename) {
        InstanceForm f = new InstanceForm();
        f.setName(name); f.setHostId(1L); f.setCatalinaBase(catalinaBase); f.setAppBase("webapps");
        f.setHealthUrl("http://10.0.0.5:8080/health"); f.setDeploymentFilename(filename);
        return f;
    }
}
