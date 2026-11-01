package com.tomcatmanager.infrastructure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Tomcat and Java versions reported by a Tomcat Manager {@code serverinfo} call,
 * e.g. {@code "[Apache Tomcat/10.1.30], JVM [21.0.4]"}, so they can be offered to the user.
 */
public record RuntimeDetection(String tomcatVersion, Integer javaVersion, String source) {
    private static final Pattern TOMCAT = Pattern.compile("Apache Tomcat/(\\d+(?:\\.\\d+){0,2})");
    private static final Pattern JVM = Pattern.compile("JVM \\[?(\\d+)(?:\\.(\\d+))?");

    public static RuntimeDetection parse(String serverInfo) {
        if (serverInfo == null) return null;
        Matcher tomcat = TOMCAT.matcher(serverInfo);
        if (!tomcat.find()) return null;
        Integer java = null;
        Matcher jvm = JVM.matcher(serverInfo);
        if (jvm.find()) {
            int major = Integer.parseInt(jvm.group(1));
            // Java 8 and older report "1.8.0_x".
            java = major == 1 && jvm.group(2) != null ? Integer.parseInt(jvm.group(2)) : major;
        }
        return new RuntimeDetection(tomcat.group(1), java, serverInfo);
    }
}
