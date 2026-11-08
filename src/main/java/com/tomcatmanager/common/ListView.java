package com.tomcatmanager.common;

import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One page of an already filtered list, plus the links a list page needs: pages, previous/next and
 * "remove this filter" chips. Links are relative query strings ({@code ?q=x&page=2}), so they work under
 * any context path. Page numbers in URLs are 1-based.
 */
public final class ListView<T> {
    public static final List<Integer> PAGE_SIZES = List.of(10, 25, 50, 100);
    public static final int DEFAULT_SIZE = 25;

    /** A numbered page link; {@code number == null} marks a gap ("…"). */
    public record PageLink(Integer number, String url, boolean current) {
        public boolean gap() { return number == null; }
    }

    /** An active filter shown as a removable chip. */
    public record Chip(String label, String removeUrl) {}

    private final List<T> content;
    private final int number;          // 0-based
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final Map<String, String> params;   // every non-default parameter except page
    private final List<Chip> chips = new ArrayList<>();

    private ListView(List<T> all, int requestedPage, int size, Map<String, String> params) {
        this.size = size;
        this.totalElements = all.size();
        this.totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) size));
        this.number = Math.min(Math.max(requestedPage, 0), totalPages - 1);
        int from = Math.min(number * size, all.size());
        this.content = List.copyOf(all.subList(from, Math.min(from + size, all.size())));
        this.params = params;
    }

    /**
     * @param all    the filtered, sorted list
     * @param page   requested 1-based page (anything invalid means page 1; past the end means the last page)
     * @param size   requested page size (anything not in {@link #PAGE_SIZES} means {@link #DEFAULT_SIZE})
     * @param params the current filter, sort and size parameters; blank values are dropped
     */
    public static <T> ListView<T> of(List<T> all, String page, String size, Map<String, String> params) {
        int pageSize = parseInt(size, DEFAULT_SIZE);
        if (!PAGE_SIZES.contains(pageSize)) pageSize = DEFAULT_SIZE;
        Map<String, String> kept = new LinkedHashMap<>();
        params.forEach((k, v) -> { if (v != null && !v.isBlank()) kept.put(k, v.strip()); });
        if (pageSize != DEFAULT_SIZE) kept.put("size", String.valueOf(pageSize));
        return new ListView<>(all, parseInt(page, 1) - 1, pageSize, kept);
    }

    /** Adds a chip for an active filter parameter; does nothing when the parameter is not set. */
    public ListView<T> chip(String param, String label) {
        if (params.containsKey(param)) chips.add(new Chip(label, urlWithout(param)));
        return this;
    }

    public List<T> getContent() { return content; }
    public int getNumber() { return number; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public int getTotalPages() { return totalPages; }
    public boolean isFirst() { return number == 0; }
    public boolean isLast() { return number >= totalPages - 1; }
    public long getFrom() { return totalElements == 0 ? 0 : (long) number * size + 1; }
    public long getTo() { return Math.min(totalElements, (long) (number + 1) * size); }
    public List<Chip> getChips() { return Collections.unmodifiableList(chips); }
    public boolean isFiltered() { return !chips.isEmpty(); }
    public String getPrevUrl() { return pageUrl(number); }
    public String getNextUrl() { return pageUrl(number + 2); }
    /** Same path, no parameters. */
    public String getClearUrl() { return "?"; }

    /** First, last, and up to two pages either side of the current one, with gaps. */
    public List<PageLink> getLinks() {
        List<PageLink> links = new ArrayList<>();
        int last = totalPages - 1;
        for (int i = 0; i <= last; i++) {
            boolean show = i == 0 || i == last || Math.abs(i - number) <= 2;
            if (show) links.add(new PageLink(i + 1, pageUrl(i + 1), i == number));
            else if (!links.isEmpty() && !links.get(links.size() - 1).gap()) links.add(new PageLink(null, null, false));
        }
        return links;
    }

    private String pageUrl(int oneBasedPage) {
        Map<String, String> p = new LinkedHashMap<>(params);
        if (oneBasedPage > 1) p.put("page", String.valueOf(oneBasedPage));
        return build(p);
    }

    private String urlWithout(String param) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.remove(param);
        return build(p);
    }

    private static String build(Map<String, String> p) {
        if (p.isEmpty()) return "?";
        UriComponentsBuilder b = UriComponentsBuilder.newInstance();
        p.forEach(b::queryParam);
        return b.encode().build().toUriString();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.strip()); } catch (NumberFormatException e) { return fallback; }
    }
}
