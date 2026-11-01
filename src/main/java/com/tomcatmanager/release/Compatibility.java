package com.tomcatmanager.release;

import com.tomcatmanager.application.ManagedApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a release's requirements with a Tomcat instance's known runtime. Missing information is
 * reported as UNKNOWN; it is never treated as compatible. A known conflict is INCOMPATIBLE.
 */
public final class Compatibility {
    public enum Status { COMPATIBLE, UNKNOWN, INCOMPATIBLE }

    public record Requirements(Integer minJava, Integer maxJava, StoredArtifact.ServletApi servletApi, List<String> tomcatLines,
                               boolean minJavaDetected, boolean servletApiDetected) { }

    public record Result(Status status, List<String> reasons) {
        public String badgeClass() {
            return switch (status) { case COMPATIBLE -> "text-bg-success"; case UNKNOWN -> "text-bg-warning"; case INCOMPATIBLE -> "text-bg-danger"; };
        }
        public String label() {
            return switch (status) { case COMPATIBLE -> "Compatible"; case UNKNOWN -> "Unknown"; case INCOMPATIBLE -> "Incompatible"; };
        }
    }

    private static final Pattern TOMCAT_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.\\d+)?.*$");

    private Compatibility() { }

    /** Application settings take precedence; detected values fill the gaps (and raise the Java minimum). */
    public static Requirements requirements(ManagedApplication app, StoredArtifact artifact) {
        Integer detectedJava = artifact.getDetectedJavaVersion();
        Integer minJava = app.getMinJavaVersion();
        boolean minDetected = false;
        if (detectedJava != null && (minJava == null || detectedJava > minJava)) { minJava = detectedJava; minDetected = true; }
        StoredArtifact.ServletApi api;
        boolean apiDetected = false;
        switch (app.getServletApi()) {
            case JAVAX -> api = StoredArtifact.ServletApi.JAVAX;
            case JAKARTA -> api = StoredArtifact.ServletApi.JAKARTA;
            default -> { api = artifact.getDetectedServletApi(); apiDetected = true; }
        }
        return new Requirements(minJava, app.getMaxJavaVersion(), api, app.getSupportedTomcatList(), minDetected, apiDetected);
    }

    public static Result evaluate(Requirements req, String tomcatVersion, Integer javaVersion) {
        List<String> incompatible = new ArrayList<>(), unknown = new ArrayList<>(), ok = new ArrayList<>();

        Integer tomcatMajor = null, tomcatMinor = null;
        if (tomcatVersion != null && !tomcatVersion.isBlank()) {
            Matcher m = TOMCAT_VERSION.matcher(tomcatVersion.trim());
            if (m.matches()) { tomcatMajor = Integer.valueOf(m.group(1)); tomcatMinor = m.group(2) == null ? null : Integer.valueOf(m.group(2)); }
        }

        // Servlet API namespace versus Tomcat generation.
        if (req.servletApi() == StoredArtifact.ServletApi.UNKNOWN) unknown.add("Servlet API (javax or jakarta) is unknown; set it on the application.");
        else if (tomcatMajor == null) unknown.add("Tomcat version of the instance is unknown.");
        else if (req.servletApi() == StoredArtifact.ServletApi.JAKARTA && tomcatMajor <= 9)
            incompatible.add("Uses jakarta.servlet, but Tomcat " + tomcatVersion + " only supports javax.servlet (Tomcat 10+ required).");
        else if (req.servletApi() == StoredArtifact.ServletApi.JAVAX && tomcatMajor >= 10)
            incompatible.add("Uses javax.servlet, but Tomcat " + tomcatVersion + " requires jakarta.servlet (Tomcat 9 or older required).");
        else ok.add("Servlet API matches Tomcat " + tomcatVersion + ".");

        // Supported Tomcat lines declared on the application.
        if (!req.tomcatLines().isEmpty() && tomcatMajor != null) {
            String line = tomcatMajor + "." + (tomcatMinor == null ? "" : tomcatMinor);
            boolean listed = tomcatMinor != null && req.tomcatLines().contains(line);
            if (!listed) incompatible.add("Tomcat " + tomcatVersion + " is not in the supported versions (" + String.join(", ", req.tomcatLines()) + ").");
        }

        // Java runtime.
        if (javaVersion == null) unknown.add("Java version of the instance is unknown.");
        else {
            if (req.minJava() == null) unknown.add("Required Java version is unknown; set a minimum on the application.");
            else if (javaVersion < req.minJava())
                incompatible.add("Needs Java " + req.minJava() + "+" + (req.minJavaDetected() ? " (detected from compiled classes)" : "") + ", instance runs Java " + javaVersion + ".");
            else ok.add("Java " + javaVersion + " meets the minimum of " + req.minJava() + ".");
            if (req.maxJava() != null && javaVersion > req.maxJava())
                incompatible.add("Supports Java up to " + req.maxJava() + ", instance runs Java " + javaVersion + ".");
        }

        List<String> reasons = new ArrayList<>(incompatible);
        reasons.addAll(unknown);
        if (incompatible.isEmpty() && unknown.isEmpty()) reasons.addAll(ok);
        Status status = !incompatible.isEmpty() ? Status.INCOMPATIBLE : !unknown.isEmpty() ? Status.UNKNOWN : Status.COMPATIBLE;
        return new Result(status, reasons);
    }
}
