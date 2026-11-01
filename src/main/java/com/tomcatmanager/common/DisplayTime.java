package com.tomcatmanager.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formats persisted UTC instants in the configured display time zone. Use in templates as {@code @time.format(x)}. */
@Component("time")
public class DisplayTime {
    private final DateTimeFormatter formatter;
    private final ZoneId zone;

    public DisplayTime(@Value("${app.display-time-zone:}") String zone) {
        this.zone = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(this.zone);
    }

    public String format(Instant instant) { return instant == null ? "—" : formatter.format(instant); }
    public String zone() { return zone.getId(); }
    /** Human-readable byte size, e.g. 12.4 MB. */
    public String size(long bytes) { return com.tomcatmanager.release.ArtifactStorage.humanSize(bytes); }
    /** Elapsed time between two instants, e.g. "45 s" or "3 min 12 s"; "—" when unknown. */
    public String duration(Instant from, Instant to) {
        if (from == null || to == null) return "—";
        long s = Math.max(0, java.time.Duration.between(from, to).toSeconds());
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }
}
