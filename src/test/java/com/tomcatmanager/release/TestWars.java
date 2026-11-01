package com.tomcatmanager.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small but structurally real WAR files for tests. */
public final class TestWars {
    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    public static TestWars war() { return new TestWars(); }

    public TestWars webXml(String namespace) {
        return entry("WEB-INF/web.xml", ("<?xml version=\"1.0\"?><web-app xmlns=\"" + namespace + "\" version=\"6.0\"></web-app>").getBytes(StandardCharsets.UTF_8));
    }
    public TestWars jakartaWebXml() { return webXml("https://jakarta.ee/xml/ns/jakartaee"); }
    public TestWars javaxWebXml() { return webXml("http://xmlns.jcp.org/xml/ns/javaee"); }

    /** A class file header for the given Java version, optionally referencing a servlet package. */
    public TestWars clazz(String name, int javaVersion, String reference) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int major = javaVersion + 44;
        out.writeBytes(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, (byte) (major >> 8), (byte) major});
        if (reference != null) out.writeBytes(reference.getBytes(StandardCharsets.US_ASCII));
        return entry("WEB-INF/classes/" + name + ".class", out.toByteArray());
    }

    public TestWars entry(String name, byte[] content) { entries.put(name, content); return this; }
    public TestWars entry(String name, String content) { return entry(name, content.getBytes(StandardCharsets.UTF_8)); }

    public byte[] bytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    Path writeTo(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) { out.write(bytes()); }
        return file;
    }
}
