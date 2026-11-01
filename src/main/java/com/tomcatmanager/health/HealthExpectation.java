package com.tomcatmanager.health;

import com.tomcatmanager.infrastructure.TomcatInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * What counts as a healthy response: accepted HTTP status codes ("200-299", "200,204", "200-399")
 * and, optionally, text the response body must contain. Redirects are never followed, so a 3xx is
 * healthy only when explicitly accepted.
 */
public record HealthExpectation(String statusSpec, List<int[]> ranges, String bodyText) {
    public static final String DEFAULT_STATUS = "200-299";
    public static final HealthExpectation DEFAULT = of(null, null);

    public static HealthExpectation of(String statusSpec, String bodyText) {
        String spec = statusSpec == null || statusSpec.isBlank() ? DEFAULT_STATUS : statusSpec.replace(" ", "");
        if (statusProblem(spec) != null) throw new IllegalArgumentException(statusProblem(spec));
        List<int[]> ranges = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] bounds = part.split("-");
            int low = Integer.parseInt(bounds[0]);
            ranges.add(new int[] {low, bounds.length == 2 ? Integer.parseInt(bounds[1]) : low});
        }
        return new HealthExpectation(spec, List.copyOf(ranges), bodyText == null || bodyText.isBlank() ? null : bodyText.strip());
    }

    public static HealthExpectation forInstance(TomcatInstance instance) {
        return of(instance.getHealthExpectedStatus(), instance.getHealthExpectedText());
    }

    /** Validation message for a status specification, or null when it is valid (blank means the default). */
    public static String statusProblem(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String s = spec.replace(" ", "");
        if (!s.matches("^\\d{3}(-\\d{3})?(,\\d{3}(-\\d{3})?){0,9}$"))
            return "Use status codes or ranges separated by commas, e.g. 200-299 or 200,204.";
        for (String part : s.split(",")) {
            String[] b = part.split("-");
            int low = Integer.parseInt(b[0]), high = b.length == 2 ? Integer.parseInt(b[1]) : low;
            if (low < 100 || high > 599) return "Status codes must be between 100 and 599.";
            if (low > high) return "In a range the first code must not be larger than the second (" + part + ").";
            if (low < 200) return "1xx codes cannot be accepted as healthy.";
        }
        return null;
    }

    public boolean statusAccepted(int status) {
        for (int[] r : ranges) if (status >= r[0] && status <= r[1]) return true;
        return false;
    }

    public boolean isDefault() { return DEFAULT_STATUS.equals(statusSpec) && bodyText == null; }

    public String describe() {
        return "HTTP " + statusSpec + (bodyText == null ? "" : " containing \"" + bodyText + "\"");
    }
}
