package com.tomcatmanager.infrastructure;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules for the WAR filename a release is renamed to on a Tomcat instance. Tomcat derives the
 * context path from it: {@code appA.war} → {@code /appA}, {@code ROOT.war} → {@code /},
 * {@code shop#admin.war} → {@code /shop/admin}, {@code app##2.0.war} → {@code /app} (version 2.0).
 */
public final class DeploymentFilename {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._#-]{0,195}\\.war$");

    private DeploymentFilename() { }

    /** Returns a user-facing problem description, or null when the name is acceptable. */
    public static String problem(String filename) {
        if (filename == null || filename.isBlank()) return "Enter the WAR filename to deploy as, e.g. appA.war.";
        String f = filename.trim();
        if (f.contains("/") || f.contains("\\")) return "Enter a file name only, without a directory.";
        if (!f.toLowerCase(Locale.ROOT).endsWith(".war")) return "The file name must end with .war.";
        if (!VALID.matcher(f).matches()) return "Use letters, digits, '.', '_', '-' and '#' only, starting with a letter or digit.";
        String base = f.substring(0, f.length() - 4);
        String name = base.contains("##") ? base.substring(0, base.indexOf("##")) : base;
        if (name.isEmpty()) return "The name before .war is empty.";
        if (name.startsWith("#") || name.endsWith("#") || name.contains("##") || name.contains("#.") || name.contains(".#"))
            return "Use '#' only between path segments, e.g. shop#admin.war.";
        if (base.contains("##") && base.substring(base.indexOf("##") + 2).isEmpty()) return "Add a version after '##', e.g. app##2.0.war.";
        return null;
    }

    public static boolean isValid(String filename) { return problem(filename) == null; }

    /** Tomcat context path for a valid filename. */
    public static String contextPath(String filename) {
        String base = filename.trim().substring(0, filename.trim().length() - 4);
        if (base.contains("##")) base = base.substring(0, base.indexOf("##"));
        if (base.equals("ROOT")) return "/";
        return "/" + base.replace('#', '/');
    }

    /** Tomcat parallel-deployment version (the part after {@code ##}), or null. */
    public static String version(String filename) {
        String base = filename.trim().substring(0, filename.trim().length() - 4);
        return base.contains("##") ? base.substring(base.indexOf("##") + 2) : null;
    }
}
