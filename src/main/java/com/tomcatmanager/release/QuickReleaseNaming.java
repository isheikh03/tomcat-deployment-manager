package com.tomcatmanager.release;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Quick Deployment naming: the application name and version label taken from an uploaded WAR.
 * {@code customer-portal-2.4.1.war} → customer-portal 2.4.1, {@code shop##1.2.war} → shop 1.2,
 * {@code app-1.0-SNAPSHOT.war} → app 1.0-SNAPSHOT. Without a version in the file name the manifest's
 * Implementation-Version is used, else the upload time.
 */
public final class QuickReleaseNaming {
    /** name, separator, optional "v", then a version starting with a digit (2.4.1, 1.0-SNAPSHOT, 2024-01-05). */
    private static final Pattern NAME_VERSION = Pattern.compile("^(.+?)[-_][vV]?(\\d+(?:\\.\\d+)*(?:[-.+_][A-Za-z0-9]+)*)$");
    private static final Pattern FILENAME_UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private QuickReleaseNaming() { }

    /** Application name from the WAR file name (the part before the version). */
    public static String applicationName(String filename) {
        String base = base(filename);
        if (base.contains("##")) return base.substring(0, base.indexOf("##"));
        Matcher m = NAME_VERSION.matcher(base);
        return m.matches() ? m.group(1) : base;
    }

    /** Version label from the WAR file name, or null when it carries none. */
    public static String versionFromFilename(String filename) {
        String base = base(filename);
        String version = base.contains("##") ? base.substring(base.indexOf("##") + 2) : null;
        if (version == null) {
            Matcher m = NAME_VERSION.matcher(base);
            version = m.matches() ? m.group(2) : null;
        }
        return version != null && ReleaseService.VERSION_LABEL.matcher(version).matches() ? version : null;
    }

    /** Implementation-Version of META-INF/MANIFEST.MF (bounded read), or null. */
    public static String versionFromManifest(Path war) {
        try (ZipFile zip = new ZipFile(war.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) return null;
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) { bytes = in.readNBytes(MAX_MANIFEST_BYTES); }
            String version = new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes().getValue("Implementation-Version");
            version = version == null ? null : version.trim();
            return version != null && ReleaseService.VERSION_LABEL.matcher(version).matches() ? version : null;
        } catch (IOException | RuntimeException e) {
            return null; // best effort: the WAR itself was already validated by the inspector
        }
    }

    /** Version label: file name, else manifest, else the upload time (UTC). */
    public static String versionLabel(String filename, Path war) {
        String version = versionFromFilename(filename);
        if (version == null) version = versionFromManifest(war);
        return version != null ? version : "quick-" + ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
    }

    /** Default WAR name to deploy as: the application name made file-name safe, e.g. "Customer Portal" → Customer-Portal.war. */
    public static String deploymentFilename(String applicationName) {
        String name = FILENAME_UNSAFE.matcher(applicationName.strip()).replaceAll("-").replaceAll("^[^A-Za-z0-9]+", "").replaceAll("[-.]+$", "");
        if (name.length() > 150) name = name.substring(0, 150);
        return (name.isEmpty() ? "app" : name) + ".war";
    }

    private static String base(String filename) {
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return name.toLowerCase().endsWith(".war") ? name.substring(0, name.length() - 4) : name;
    }
}
