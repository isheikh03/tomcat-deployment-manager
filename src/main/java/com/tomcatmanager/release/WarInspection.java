package com.tomcatmanager.release;

import java.util.List;

/**
 * What could be learned from a WAR without running it. Detection is best-effort and is labelled
 * as "detected" in the UI; application-level settings take precedence.
 */
public record WarInspection(int entryCount, boolean hasWebXml, Integer javaVersion, StoredArtifact.ServletApi servletApi, List<String> notes) {
    public String notesText() {
        String text = String.join(" ", notes);
        return text.isBlank() ? null : (text.length() > 1000 ? text.substring(0, 999) + "…" : text);
    }
}
