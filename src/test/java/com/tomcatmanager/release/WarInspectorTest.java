package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarInspectorTest {
    @TempDir Path dir;
    private final WarInspector inspector = new WarInspector();

    @Test void detectsJakartaFromWebXmlAndHighestClassLevel() throws Exception {
        Path war = TestWars.war().jakartaWebXml().clazz("a/A", 11, null).clazz("a/B", 17, null).writeTo(dir.resolve("app.war"));
        WarInspection result = inspector.inspect(war);
        assertThat(result.hasWebXml()).isTrue();
        assertThat(result.javaVersion()).isEqualTo(17);
        assertThat(result.servletApi()).isEqualTo(StoredArtifact.ServletApi.JAKARTA);
        assertThat(result.entryCount()).isEqualTo(3);
    }

    @Test void detectsJavaxFromWebXml() throws Exception {
        Path war = TestWars.war().javaxWebXml().clazz("A", 8, null).writeTo(dir.resolve("legacy.war"));
        WarInspection result = inspector.inspect(war);
        assertThat(result.servletApi()).isEqualTo(StoredArtifact.ServletApi.JAVAX);
        assertThat(result.javaVersion()).isEqualTo(8);
    }

    @Test void detectsServletApiFromClassReferencesWithoutWebXml() throws Exception {
        Path war = TestWars.war().clazz("Servlet", 21, "jakarta/servlet/http/HttpServlet").writeTo(dir.resolve("annotated.war"));
        WarInspection result = inspector.inspect(war);
        assertThat(result.hasWebXml()).isFalse();
        assertThat(result.servletApi()).isEqualTo(StoredArtifact.ServletApi.JAKARTA);
        assertThat(result.javaVersion()).isEqualTo(21);
    }

    @Test void detectsServletApiFromBundledLibraries() throws Exception {
        Path war = TestWars.war().entry("WEB-INF/lib/spring-web-5.3.39.jar", "jar").entry("index.jsp", "x").writeTo(dir.resolve("spring5.war"));
        WarInspection result = inspector.inspect(war);
        assertThat(result.servletApi()).isEqualTo(StoredArtifact.ServletApi.JAVAX);
        assertThat(result.javaVersion()).isNull();
    }

    @Test void unknownWhenNothingIndicatesTheApi() throws Exception {
        Path war = TestWars.war().entry("WEB-INF/README.txt", "x").writeTo(dir.resolve("plain.war"));
        assertThat(inspector.inspect(war).servletApi()).isEqualTo(StoredArtifact.ServletApi.UNKNOWN);
    }

    @Test void rejectsNonZipFiles() throws Exception {
        Path file = Files.writeString(dir.resolve("fake.war"), "this is not a zip");
        assertThatThrownBy(() -> inspector.inspect(file)).isInstanceOf(InvalidWarException.class).hasMessageContaining("not a valid WAR");
    }

    @Test void rejectsArchivesWithoutWebInf() throws Exception {
        Path war = TestWars.war().entry("index.html", "<html></html>").writeTo(dir.resolve("static.war"));
        assertThatThrownBy(() -> inspector.inspect(war)).isInstanceOf(InvalidWarException.class).hasMessageContaining("WEB-INF");
    }

    @Test void rejectsPathTraversalEntries() throws Exception {
        Path war = TestWars.war().jakartaWebXml().entry("../../etc/cron.d/evil", "x").writeTo(dir.resolve("slip.war"));
        assertThatThrownBy(() -> inspector.inspect(war)).isInstanceOf(InvalidWarException.class).hasMessageContaining("unsafe path");
        assertThat(WarInspector.isUnsafe("/abs/path")).isTrue();
        assertThat(WarInspector.isUnsafe("C:/x")).isTrue();
        assertThat(WarInspector.isUnsafe("WEB-INF/classes/a..b.class")).isFalse();
    }
}
