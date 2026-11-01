package com.tomcatmanager.release;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Validates that a file is a web application archive and detects the Java class-file level and
 * the Servlet API namespace (javax vs jakarta). It never extracts the archive and reads at most
 * small, bounded parts of entries, so oversized ("zip bomb") archives cannot exhaust resources.
 */
@Component
public class WarInspector {
    static final int MAX_ENTRIES = 100_000;
    private static final int MAX_CLASSES_SCANNED = 3_000;
    private static final int MAX_CLASS_BYTES = 128 * 1024;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final byte[] JAKARTA = "jakarta/servlet/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JAVAX = "javax/servlet/".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern LIB_VERSION = Pattern.compile("^WEB-INF/lib/(spring-web|tomcat-embed-core|jakarta\\.servlet-api|javax\\.servlet-api)-(\\d+)[^/]*\\.jar$");

    public WarInspection inspect(Path war) throws InvalidWarException {
        try (ZipFile zip = new ZipFile(war.toFile())) {
            int entries = 0, classesScanned = 0, maxMajor = 0;
            boolean webInf = false, webXml = false, jakartaRefs = false, javaxRefs = false;
            StoredArtifact.ServletApi descriptorApi = null, libraryApi = null;
            List<String> notes = new ArrayList<>();

            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++entries > MAX_ENTRIES) throw new InvalidWarException("The archive has more than " + MAX_ENTRIES + " entries and was rejected.");
                String name = entry.getName();
                if (isUnsafe(name)) throw new InvalidWarException("The archive contains an unsafe path (" + abbreviate(name) + ") and was rejected.");
                if (name.startsWith("WEB-INF/")) webInf = true;
                if (name.equals("WEB-INF/web.xml")) {
                    webXml = true;
                    descriptorApi = servletApiOfDescriptor(read(zip, entry, MAX_DESCRIPTOR_BYTES));
                } else if (name.startsWith("WEB-INF/classes/") && name.endsWith(".class") && classesScanned < MAX_CLASSES_SCANNED) {
                    classesScanned++;
                    byte[] bytes = read(zip, entry, MAX_CLASS_BYTES);
                    if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE && (bytes[2] & 0xFF) == 0xBA && (bytes[3] & 0xFF) == 0xBE) {
                        maxMajor = Math.max(maxMajor, ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF));
                        jakartaRefs |= contains(bytes, JAKARTA);
                        javaxRefs |= contains(bytes, JAVAX);
                    }
                } else if (libraryApi == null) {
                    libraryApi = servletApiOfLibrary(name);
                }
            }
            if (entries == 0) throw new InvalidWarException("The archive is empty.");
            if (!webInf) throw new InvalidWarException("The archive has no WEB-INF/ directory, so it is not a Java web application (WAR).");

            Integer javaVersion = maxMajor >= 45 ? Math.max(1, maxMajor - 44) : null;
            if (javaVersion != null && javaVersion < 8) javaVersion = 8;
            if (javaVersion != null) notes.add("Classes compiled for Java " + javaVersion + " (class file " + maxMajor + ").");
            else notes.add("No compiled classes found in WEB-INF/classes; the required Java version could not be detected.");
            if (classesScanned >= MAX_CLASSES_SCANNED) notes.add("Only the first " + MAX_CLASSES_SCANNED + " classes were scanned.");

            StoredArtifact.ServletApi api;
            if (descriptorApi != null) { api = descriptorApi; notes.add("Servlet API from web.xml namespace: " + label(api) + "."); }
            else if (jakartaRefs != javaxRefs) { api = jakartaRefs ? StoredArtifact.ServletApi.JAKARTA : StoredArtifact.ServletApi.JAVAX; notes.add("Servlet API from class references: " + label(api) + "."); }
            else if (libraryApi != null) { api = libraryApi; notes.add("Servlet API inferred from bundled libraries: " + label(api) + "."); }
            else { api = StoredArtifact.ServletApi.UNKNOWN; notes.add(jakartaRefs ? "Both javax.servlet and jakarta.servlet are referenced." : "The Servlet API (javax or jakarta) could not be detected."); }
            if (!webXml) notes.add("No WEB-INF/web.xml (annotation- or Spring Boot-configured application).");

            return new WarInspection(entries, webXml, javaVersion, api, notes);
        } catch (ZipException e) {
            throw new InvalidWarException("The file is not a valid WAR (ZIP) archive.");
        } catch (IOException e) {
            throw new InvalidWarException("The archive could not be read: " + e.getMessage());
        }
    }

    static boolean isUnsafe(String name) {
        if (name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) return true;
        for (String segment : name.split("/")) if (segment.equals("..")) return true;
        return false;
    }

    private static StoredArtifact.ServletApi servletApiOfDescriptor(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        if (text.contains("jakarta.ee/xml/ns/jakartaee")) return StoredArtifact.ServletApi.JAKARTA;
        if (text.contains("xmlns.jcp.org/xml/ns/javaee") || text.contains("java.sun.com/xml/ns/javaee") || text.contains("java.sun.com/xml/ns/j2ee")
                || text.contains("java.sun.com/dtd/web-app")) return StoredArtifact.ServletApi.JAVAX;
        return null;
    }

    private static StoredArtifact.ServletApi servletApiOfLibrary(String name) {
        Matcher m = LIB_VERSION.matcher(name);
        if (!m.matches()) return null;
        int major = Integer.parseInt(m.group(2));
        return switch (m.group(1)) {
            case "spring-web" -> major >= 6 ? StoredArtifact.ServletApi.JAKARTA : StoredArtifact.ServletApi.JAVAX;
            case "tomcat-embed-core" -> major >= 10 ? StoredArtifact.ServletApi.JAKARTA : StoredArtifact.ServletApi.JAVAX;
            case "jakarta.servlet-api" -> major >= 5 ? StoredArtifact.ServletApi.JAKARTA : StoredArtifact.ServletApi.JAVAX;
            default -> StoredArtifact.ServletApi.JAVAX;
        };
    }

    private static byte[] read(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) { return in.readNBytes(limit); }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static String label(StoredArtifact.ServletApi api) {
        return switch (api) { case JAKARTA -> "jakarta.servlet (Tomcat 10+)"; case JAVAX -> "javax.servlet (Tomcat 9 and older)"; default -> "unknown"; };
    }

    private static String abbreviate(String s) { return s.length() > 80 ? s.substring(0, 79) + "…" : s; }
}
