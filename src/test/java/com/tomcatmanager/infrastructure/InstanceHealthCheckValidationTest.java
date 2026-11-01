package com.tomcatmanager.infrastructure;

import com.tomcatmanager.security.SecretEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceHealthCheckValidationTest {
    private InstanceService service() {
        ManagedHost host = new ManagedHost(); host.setId(1L); host.setHostname("10.0.0.5");
        TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
        ManagedHostRepository hosts = mock(ManagedHostRepository.class);
        when(instances.findByHostIdOrderByNameAsc(1L)).thenReturn(List.of());
        when(hosts.findById(1L)).thenReturn(Optional.of(host));
        return new InstanceService(instances, hosts, mock(ServerGroupRepository.class), new SecretEncryptionService(""));
    }

    private static InstanceForm form(boolean healthCheck, String url, Integer port) {
        InstanceForm f = new InstanceForm();
        f.setName("a"); f.setHostId(1L); f.setEnvironment(Environment.DEVELOPMENT); f.setCatalinaBase("/opt/tomcat/a"); f.setAppBase("webapps");
        f.setDeploymentFilename("app.war"); f.setHealthCheckEnabled(healthCheck); f.setHealthUrl(url); f.setHttpPort(port);
        return f;
    }

    private static BeanPropertyBindingResult validate(InstanceService service, InstanceForm f) {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(f, "form");
        service.validate(f, errors);
        return errors;
    }

    @Test void healthUrlIsRequiredWhileTheCheckIsOn() {
        BeanPropertyBindingResult errors = validate(service(), form(true, "", 8080));
        assertThat(errors.getFieldError("healthUrl").getDefaultMessage()).contains("switch off the health check");
    }

    @Test void urlAndPortAreOptionalWhenTheCheckIsOff() {
        assertThat(validate(service(), form(false, null, null)).hasErrors()).isFalse();
    }

    @Test void anOptionalUrlMustStillBeValidIfEntered() {
        assertThat(validate(service(), form(false, "not a url", null)).getFieldError("healthUrl")).isNotNull();
        assertThat(validate(service(), form(true, "http://10.0.0.5:8080{context}/health", null)).hasErrors()).isFalse();
    }
}
