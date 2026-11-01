package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.connectivity.RemoteOperationException;
import com.tomcatmanager.connectivity.SshConnectivityClient;
import com.tomcatmanager.connectivity.TomcatManagerClient;
import com.tomcatmanager.infrastructure.TomcatInstance;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Read-only look at a target before deploying: is something already deployed at the context, and how much space is free. */
@Component
public class RemoteInspector {
    private final ConnectionTestService connections;
    private final SshConnectivityClient ssh;
    private final TomcatManagerClient manager;

    public RemoteInspector(ConnectionTestService connections, SshConnectivityClient ssh, TomcatManagerClient manager) {
        this.connections = connections; this.ssh = ssh; this.manager = manager;
    }

    /**
     * @param applicationPresent something is deployed at the target file/context now
     * @param warPresent         (SSH) the WAR file itself exists and can be backed up
     * @param freeBytes          (SSH) free space in the deployment directory, null if unknown
     */
    public record Inspection(boolean applicationPresent, boolean warPresent, Long freeBytes, String description) { }

    public Inspection inspect(TomcatInstance instance, String filename, String contextPath) throws RemoteOperationException {
        Duration timeout = Duration.ofSeconds(instance.getTimeoutSeconds());
        if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
            String dir = instance.getRemoteDeploymentDirectory();
            try (ClientSession session = ssh.openSession(connections.sshTargetFor(instance.getHost(), timeout));
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                SftpOperations.RemoteState state = SftpOperations.inspect(sftp, dir, filename);
                String description = state.warExists() ? filename + " exists (" + humanSize(state.warSize()) + ")"
                        : state.explodedExists() ? "An exploded directory exists without " + filename : "Nothing deployed at " + contextPath;
                return new Inspection(state.applicationPresent(), state.warExists(), state.freeBytes(), description);
            } catch (RemoteOperationException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteOperationException("Could not inspect " + dir + ": " + DiagnosticSanitizer.describe(e), e);
            }
        }
        try {
            Map<String, String> apps = manager.listApplications(connections.managerTargetFor(instance, timeout));
            String state = apps.get(contextPath);
            return new Inspection(state != null, false, null, state == null ? "Nothing deployed at " + contextPath : contextPath + " is " + state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteOperationException("Inspection was interrupted.");
        } catch (RemoteOperationException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteOperationException("Could not query Tomcat Manager: " + DiagnosticSanitizer.describe(e), e);
        }
    }

    /** What is actually deployed at a target now, for reconciling an unknown outcome. Read-only. */
    public record Evidence(boolean warPresent, String warSha256, Long warSize, String managerState, String description) { }

    public Evidence evidence(TomcatInstance instance, String filename, String contextPath) throws RemoteOperationException {
        Duration timeout = Duration.ofSeconds(instance.getTimeoutSeconds());
        if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
            String path = SftpOperations.join(instance.getRemoteDeploymentDirectory(), filename);
            try (ClientSession session = ssh.openSession(connections.sshTargetFor(instance.getHost(), timeout));
                 SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                if (SftpOperations.statOrNull(sftp, path) == null) return new Evidence(false, null, null, null, path + " does not exist.");
                SftpOperations.Checksum sum = SftpOperations.remoteChecksum(sftp, path);
                return new Evidence(true, sum.sha256(), sum.size(), null, path + " exists (" + humanSize(sum.size()) + ", SHA-256 " + sum.sha256().substring(0, 12) + "…).");
            } catch (RemoteOperationException e) {
                throw e;
            } catch (IOException e) {
                throw new RemoteOperationException("Could not read " + path + ": " + DiagnosticSanitizer.describe(e), e);
            }
        }
        Inspection inspection = inspect(instance, filename, contextPath);
        String state = inspection.applicationPresent() ? inspection.description().replace(contextPath + " is ", "") : null;
        return new Evidence(false, null, null, state, inspection.description());
    }

    static String humanSize(long bytes) { return com.tomcatmanager.release.ArtifactStorage.humanSize(bytes); }
}
