package com.tomcatmanager.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The restart commands are built from fixed templates and validated values only; nothing else can be run. */
class TomcatRestartTest {
    private static TomcatInstance instance(TomcatRestart.Mode mode) {
        TomcatInstance i = new TomcatInstance();
        i.setDeploymentMethod(TomcatInstance.DeploymentMethod.SSH_SFTP);
        i.setCatalinaBase("/opt/tomcat/base");
        i.setRestartMode(mode);
        return i;
    }

    @Test void noRestartByDefault_andNeverForTomcatManager() {
        TomcatInstance i = new TomcatInstance();
        assertThat(i.getRestartMode()).isEqualTo(TomcatRestart.Mode.NONE);
        assertThat(i.restartsTomcat()).isFalse();
        assertThat(TomcatRestart.commandsFor(i)).isNull();
        TomcatInstance manager = instance(TomcatRestart.Mode.SYSTEMD);
        manager.setDeploymentMethod(TomcatInstance.DeploymentMethod.TOMCAT_MANAGER);
        assertThat(manager.restartsTomcat()).as("Tomcat Manager deployments are never restarted").isFalse();
    }

    @Test void systemdCommands() {
        TomcatInstance i = instance(TomcatRestart.Mode.SYSTEMD);
        i.setRestartService("tomcat9");
        TomcatRestart.Commands c = TomcatRestart.commandsFor(i);
        assertThat(c.stop()).isEqualTo("systemctl stop 'tomcat9'");
        assertThat(c.start()).isEqualTo("systemctl start 'tomcat9'");
        assertThat(c.status()).isEqualTo("systemctl is-active 'tomcat9' || true");
        assertThat(c.stopWaitSeconds()).isZero();

        i.setRestartUseSudo(true);
        c = TomcatRestart.commandsFor(i);
        assertThat(c.stop()).isEqualTo("sudo -n systemctl stop 'tomcat9'");
        assertThat(c.sudoCheck(c.stop())).isEqualTo("sudo -n -l systemctl stop 'tomcat9'");
    }

    @Test void scriptCommandsUseCatalinaHomeAndBase() {
        TomcatInstance i = instance(TomcatRestart.Mode.SCRIPTS);
        TomcatRestart.Commands c = TomcatRestart.commandsFor(i);
        assertThat(c.stop()).isEqualTo("env CATALINA_BASE='/opt/tomcat/base' '/opt/tomcat/base/bin/shutdown.sh'");
        assertThat(c.start()).isEqualTo("env CATALINA_BASE='/opt/tomcat/base' '/opt/tomcat/base/bin/startup.sh'");
        assertThat(c.stopWaitSeconds()).isEqualTo(TomcatRestart.DEFAULT_STOP_WAIT_SECONDS);
        assertThat(c.scripts()).containsExactly("/opt/tomcat/base/bin/shutdown.sh", "/opt/tomcat/base/bin/startup.sh");

        i.setCatalinaHome("/opt/apache-tomcat-10.1/"); i.setRestartUseSudo(true); i.setRestartStopWaitSeconds(30);
        c = TomcatRestart.commandsFor(i);
        assertThat(c.start()).isEqualTo("sudo -n env CATALINA_BASE='/opt/tomcat/base' '/opt/apache-tomcat-10.1/bin/startup.sh'");
        assertThat(c.stopWaitSeconds()).isEqualTo(30);
    }

    @Test void nothingButValidatedValuesReachTheCommand() {
        for (String evil : new String[]{"tomcat; rm -rf /", "tomcat' && reboot '", "$(reboot)", "tomcat`id`", "a b", "-r", "tomcat\nreboot"})
            assertThat(TomcatRestart.serviceProblem(evil)).as(evil).isNotNull();
        for (String evil : new String[]{"/opt/tomcat; reboot", "/opt/to'mcat", "relative/path", "/opt/../etc", "/opt/tomcat $(id)", "/opt/a b"})
            assertThat(TomcatRestart.pathProblem(evil)).as(evil).isNotNull();
        assertThat(TomcatRestart.serviceProblem("tomcat@app1.service")).isNull();
        assertThat(TomcatRestart.pathProblem("/opt/apache-tomcat-10.1.30")).isNull();

        // Settings stored by other means are checked again before any command is built.
        TomcatInstance i = instance(TomcatRestart.Mode.SYSTEMD);
        i.setRestartService("tomcat; reboot");
        assertThatThrownBy(() -> TomcatRestart.commandsFor(i)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Restart settings");
        TomcatInstance s = instance(TomcatRestart.Mode.SCRIPTS);
        s.setCatalinaBase("/opt/tomcat base");
        assertThatThrownBy(() -> TomcatRestart.commandsFor(s)).isInstanceOf(IllegalArgumentException.class);
    }
}
