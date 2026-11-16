package com.tomcatmanager.infrastructure;

import com.tomcatmanager.connectivity.EmbeddedSshServer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tomcat discovery over a real SFTP server (Apache MINA SSHD on 127.0.0.1) serving a fake Linux file tree:
 * a running Tomcat found through /proc, one through its systemd unit, one by folder scan; Tomcat's own apps are ignored.
 */
class TomcatScanTest {
    @TempDir Path root;
    private EmbeddedSshServer server;
    private SshClient client;
    private ClientSession session;
    private SftpClient sftp;

    private static final String SERVER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Server port="8005" shutdown="SHUTDOWN">
              <Service name="Catalina">
                <Connector port="%d" protocol="HTTP/1.1" connectionTimeout="20000"/>
                <Connector port="8009" protocol="AJP/1.3"/>
                <Engine name="Catalina" defaultHost="localhost">
                  <Host name="localhost" appBase="%s" unpackWARs="true" autoDeploy="%s"/>
                </Engine>
              </Service>
            </Server>
            """;

    private void file(String path, String content) throws Exception {
        Path p = root.resolve(path.substring(1));
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private void dir(String path) throws Exception { Files.createDirectories(root.resolve(path.substring(1))); }

    @BeforeEach void serverWithTomcats() throws Exception {
        // 1. Running: base /opt/tomcat-a, home /opt/apache-tomcat-10.1.30, Java 17.
        file("/proc/123/cmdline", String.join("\u0000", "/usr/lib/jvm/java-17-openjdk-amd64/bin/java", "-Xmx512m",
                "-Dcatalina.base=/opt/tomcat-a", "-Dcatalina.home=/opt/apache-tomcat-10.1.30", "org.apache.catalina.startup.Bootstrap", "start"));
        file("/proc/456/cmdline", "/usr/bin/bash\u0000-l");
        file("/opt/tomcat-a/conf/server.xml", SERVER_XML.formatted(8080, "webapps", "true"));
        file("/opt/tomcat-a/webapps/portal.war", "war");
        file("/opt/tomcat-a/webapps/shop.war", "war");
        dir("/opt/tomcat-a/webapps/shop");          // unpacked copy of shop.war: not offered twice
        dir("/opt/tomcat-a/webapps/ROOT");          // unpacked only
        dir("/opt/tomcat-a/webapps/manager");       // Tomcat's own
        file("/opt/apache-tomcat-10.1.30/RELEASE-NOTES", "...\n                     Apache Tomcat Version 10.1.30\n...");

        // 2. systemd unit; appBase is absolute and autoDeploy is off.
        file("/etc/systemd/system/tomcat-billing.service", "[Service]\nEnvironment=\"CATALINA_HOME=/srv/tomcat-billing\" \"CATALINA_BASE=/srv/tomcat-billing\"\n"
                + "ExecStart=/srv/tomcat-billing/bin/startup.sh\n");
        file("/srv/tomcat-billing/conf/server.xml", SERVER_XML.formatted(9090, "/data/apps", "false"));
        file("/data/apps/billing.war", "war");
        file("/etc/systemd/system/sshd.service", "[Service]\nExecStart=/usr/sbin/sshd\n");

        // 3. Folder only (two levels down), no applications yet, version from the folder name.
        file("/home/deploy/apache-tomcat-9.0.98/conf/server.xml", SERVER_XML.formatted(8180, "webapps", "true"));
        dir("/home/deploy/apache-tomcat-9.0.98/webapps");

        // A server.xml with a DOCTYPE (possible XXE) is not parsed: defaults are used.
        file("/opt/evil/conf/server.xml", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Server><Connector port=\"&e;\"/></Server>");

        server = new EmbeddedSshServer(root, null, false);
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        session = client.connect(EmbeddedSshServer.USER, "127.0.0.1", server.port()).verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity(EmbeddedSshServer.PASSWORD);
        session.auth().verify(Duration.ofSeconds(10));
        sftp = SftpClientFactory.instance().createSftpClient(session);
    }

    @AfterEach void stop() throws Exception {
        if (sftp != null) sftp.close();
        if (session != null) session.close();
        if (client != null) client.stop();
        if (server != null) server.close();
    }

    @Test void findsRunningSystemdAndFolderTomcatsWithTheirApplications() {
        TomcatScan.Result result = TomcatScan.scan(sftp, Duration.ofSeconds(30));
        Map<String, TomcatScan.Tomcat> byBase = result.tomcats().stream().collect(Collectors.toMap(TomcatScan.Tomcat::catalinaBase, Function.identity()));

        TomcatScan.Tomcat a = byBase.get("/opt/tomcat-a");
        assertThat(a).isNotNull();
        assertThat(a.running()).isTrue();
        assertThat(a.httpPort()).as("HTTP connector, not AJP").isEqualTo(8080);
        assertThat(a.tomcatVersion()).isEqualTo("10.1.30");
        assertThat(a.javaVersion()).isEqualTo(17);
        assertThat(a.deploymentDirectory()).isEqualTo("/opt/tomcat-a/webapps");
        assertThat(a.apps()).extracting(TomcatScan.App::filename).containsExactly("portal.war", "ROOT.war", "shop.war");
        assertThat(a.apps()).filteredOn(TomcatScan.App::exploded).extracting(TomcatScan.App::filename).containsExactly("ROOT.war");

        TomcatScan.Tomcat billing = byBase.get("/srv/tomcat-billing");
        assertThat(billing).isNotNull();
        assertThat(billing.systemdService()).isEqualTo("tomcat-billing");
        assertThat(billing.running()).isFalse();
        assertThat(billing.httpPort()).isEqualTo(9090);
        assertThat(billing.appBase()).isEqualTo("/data/apps");
        assertThat(billing.autoDeploy()).isFalse();
        assertThat(billing.apps()).extracting(TomcatScan.App::filename).containsExactly("billing.war");

        TomcatScan.Tomcat folder = byBase.get("/home/deploy/apache-tomcat-9.0.98");
        assertThat(folder).isNotNull();
        assertThat(folder.tomcatVersion()).isEqualTo("9.0.98");
        assertThat(folder.apps()).isEmpty();

        TomcatScan.Tomcat evil = byBase.get("/opt/evil");
        assertThat(evil).as("found, but its server.xml is not trusted").isNotNull();
        assertThat(evil.httpPort()).isNull();
        assertThat(evil.appBase()).isEqualTo("webapps");
    }

    @Test void suggestionsSkipWhatIsAlreadyAddedAndHaveUniqueNames() {
        TomcatScan.Result result = TomcatScan.scan(sftp, Duration.ofSeconds(30));
        ManagedHost host = new ManagedHost(); host.setId(1L); host.setHostname("app01.example.com");
        TomcatInstance existing = new TomcatInstance() { @Override public Long getId() { return 9L; } };
        existing.setName("tomcat-a-portal"); existing.setHost(host); existing.setCatalinaBase("/opt/tomcat-a"); existing.setAppBase("webapps");
        existing.setDeploymentFilename("portal.war");
        TomcatInstanceRepository instances = org.mockito.Mockito.mock(TomcatInstanceRepository.class);
        org.mockito.Mockito.when(instances.findByHostIdOrderByNameAsc(1L)).thenReturn(List.of(existing));
        InstanceDiscoveryController controller = new InstanceDiscoveryController(null, instances, null, null, null, null, null);

        InstanceDiscoveryController.Suggestions s = controller.suggest(host, result);
        assertThat(s.alreadyAdded()).isEqualTo(1);
        List<InstanceDiscoveryController.Row> rows = s.selection().getRows();
        assertThat(rows).extracting(InstanceDiscoveryController.Row::getDeploymentFilename)
                .contains("ROOT.war", "shop.war", "billing.war", "").doesNotContain("portal.war");
        InstanceDiscoveryController.Row shop = rows.stream().filter(r -> "shop.war".equals(r.getDeploymentFilename())).findFirst().orElseThrow();
        assertThat(shop.getName()).isEqualTo("tomcat-a-shop");
        assertThat(shop.isSelected()).isTrue();
        assertThat(shop.getHealthUrl()).isEqualTo("http://app01.example.com:8080{context}/");
        InstanceDiscoveryController.Row empty = rows.stream().filter(r -> r.getCatalinaBase().equals("/home/deploy/apache-tomcat-9.0.98")).findFirst().orElseThrow();
        assertThat(empty.isSelected()).as("no application found: the user must enter a file name").isFalse();
        assertThat(empty.getName()).isEqualTo("apache-tomcat-9.0.98");

        InstanceForm form = InstanceDiscoveryController.toForm(host, shop);
        assertThat(form.getDeploymentMethod()).isEqualTo(TomcatInstance.DeploymentMethod.SSH_SFTP);
        assertThat(form.isHealthCheckEnabled()).isFalse();
        assertThat(form.getTomcatVersion()).isEqualTo("10.1.30");
        assertThat(form.getHttpPort()).isEqualTo(8080);
    }

    @Test void helpers() {
        assertThat(TomcatScan.environmentAssignments("\"CATALINA_HOME=/a b\" CATALINA_BASE=/c")).containsExactly("CATALINA_HOME=/a b", "CATALINA_BASE=/c");
        assertThat(TomcatScan.javaVersion("/usr/lib/jvm/jdk1.8.0_202/bin/java")).isEqualTo(8);
        assertThat(TomcatScan.javaVersion("/opt/java/openjdk-21/bin/java")).isEqualTo(21);
        assertThat(TomcatScan.javaVersion("/usr/bin/java")).isNull();
        assertThat(TomcatScan.pathVersion("/usr/share/tomcat9", "/opt/apache-tomcat-11.0.2")).isEqualTo("11.0.2");
    }
}
