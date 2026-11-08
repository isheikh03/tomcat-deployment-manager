package com.tomcatmanager.infrastructure;

import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.connectivity.ConnectivityStatus.State;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/** Search, filters and sort orders for the Linux host, Tomcat instance and server group list pages. */
final class InfrastructureListing {
    /** Environment filter value for instances that have none. */
    static final String NO_ENVIRONMENT = "NONE";

    record HostFilter(String q, State connectivity, Boolean enabled, Boolean pinned, String sort) {}

    record InstanceFilter(String q, String environment, Long hostId, TomcatInstance.DeploymentMethod method,
                          Long groupId, State connectivity, Boolean enabled, String sort) {}

    private InfrastructureListing() {}

    static List<ManagedHost> hosts(List<ManagedHost> all, HostFilter f, Map<Long, ConnectivityStatus> statuses) {
        Function<ManagedHost, State> state = h -> stateOf(statuses, h.getId());
        Comparator<ManagedHost> byName = Comparator.comparing(h -> lower(h.getName()));
        Comparator<ManagedHost> order = switch (Objects.requireNonNullElse(f.sort(), "")) {
            case "name-desc" -> byName.reversed();
            case "hostname" -> Comparator.comparing((ManagedHost h) -> lower(h.getHostname())).thenComparing(byName);
            case "problems" -> Comparator.comparing((ManagedHost h) -> severity(state.apply(h))).thenComparing(byName);
            case "newest" -> Comparator.comparing(ManagedHost::getId, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> byName;
        };
        return all.stream()
                .filter(h -> matches(f.q(), h.getName(), h.getHostname(), h.getSshUsername(), h.getNotes()))
                .filter(h -> f.connectivity() == null || state.apply(h) == f.connectivity())
                .filter(h -> f.enabled() == null || h.isEnabled() == f.enabled())
                .filter(h -> f.pinned() == null || h.hasPinnedHostKey() == f.pinned())
                .sorted(order).toList();
    }

    static List<TomcatInstance> instances(List<TomcatInstance> all, InstanceFilter f, Map<Long, ConnectivityStatus> statuses) {
        Function<TomcatInstance, State> state = i -> stateOf(statuses, i.getId());
        Comparator<TomcatInstance> byName = Comparator.comparing(i -> lower(i.getName()));
        Comparator<TomcatInstance> order = switch (Objects.requireNonNullElse(f.sort(), "")) {
            case "name-desc" -> byName.reversed();
            case "host" -> Comparator.comparing((TomcatInstance i) -> lower(i.getHost().getName())).thenComparing(byName);
            case "environment" -> Comparator.comparing((TomcatInstance i) -> i.getEnvironment() == null ? Integer.MAX_VALUE : i.getEnvironment().ordinal())
                    .thenComparing(byName);
            case "problems" -> Comparator.comparing((TomcatInstance i) -> severity(state.apply(i))).thenComparing(byName);
            case "newest" -> Comparator.comparing(TomcatInstance::getId, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> byName;
        };
        return all.stream()
                .filter(i -> matches(f.q(), i.getName(), i.getHost().getName(), i.getHost().getHostname(), i.getRemoteDeploymentDirectory(),
                        i.getDeploymentFilename(), i.getDeploymentContextPath(), i.getCatalinaBase(), i.getTomcatVersion()))
                .filter(i -> f.environment() == null
                        || (NO_ENVIRONMENT.equals(f.environment()) ? i.getEnvironment() == null
                            : i.getEnvironment() != null && i.getEnvironment().name().equals(f.environment())))
                .filter(i -> f.hostId() == null || f.hostId().equals(i.getHost().getId()))
                .filter(i -> f.method() == null || i.getDeploymentMethod() == f.method())
                .filter(i -> f.groupId() == null || i.getGroups().stream().anyMatch(g -> f.groupId().equals(g.getId())))
                .filter(i -> f.connectivity() == null || state.apply(i) == f.connectivity())
                .filter(i -> f.enabled() == null || i.isEnabled() == f.enabled())
                .sorted(order).toList();
    }

    static List<ServerGroup> groups(List<ServerGroup> all, String q) {
        return all.stream().filter(g -> matches(q, g.getName(), g.getDescription()))
                .sorted(Comparator.comparing(g -> lower(g.getName()))).toList();
    }

    /** Failed first, never-tested before verified: the order to work through problems. */
    static int severity(State state) {
        return switch (state) {
            case FAILED -> 0;
            case WARNING -> 1;
            case STALE -> 2;
            case NEVER_TESTED -> 3;
            case VERIFIED -> 4;
        };
    }

    static State stateOf(Map<Long, ConnectivityStatus> statuses, Long id) {
        ConnectivityStatus s = statuses.get(id);
        return s == null ? State.NEVER_TESTED : s.state();
    }

    /** Every word of the query must appear in at least one of the fields (case-insensitive). */
    static boolean matches(String q, String... fields) {
        if (q == null || q.isBlank()) return true;
        String haystack = String.join(" ", Stream.of(fields).filter(Objects::nonNull).toList()).toLowerCase(Locale.ROOT);
        return Stream.of(q.toLowerCase(Locale.ROOT).trim().split("\\s+")).allMatch(haystack::contains);
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(type, value.strip()); } catch (IllegalArgumentException e) { return null; }
    }

    static Long parseId(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value.strip()); } catch (NumberFormatException e) { return null; }
    }

    /** "yes"/"no"-style filters: returns null (no filter) for anything other than the two given values. */
    static Boolean parseFlag(String value, String trueValue, String falseValue) {
        if (trueValue.equals(value)) return Boolean.TRUE;
        if (falseValue.equals(value)) return Boolean.FALSE;
        return null;
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
