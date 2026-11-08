package com.tomcatmanager.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ListViewTest {
    private static final List<Integer> HUNDRED = IntStream.rangeClosed(1, 100).boxed().toList();

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test void pagesAndKeepsFiltersInLinks() {
        ListView<Integer> v = ListView.of(HUNDRED, "2", "10", params("q", "web 1", "status", "enabled"));
        assertThat(v.getContent()).containsExactly(11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(v.getFrom()).isEqualTo(11);
        assertThat(v.getTo()).isEqualTo(20);
        assertThat(v.getTotalPages()).isEqualTo(10);
        assertThat(v.getPrevUrl()).isEqualTo("?q=web%201&status=enabled&size=10");
        assertThat(v.getNextUrl()).isEqualTo("?q=web%201&status=enabled&size=10&page=3");
    }

    @Test void invalidOrOutOfRangeInputFallsBackSafely() {
        ListView<Integer> junk = ListView.of(HUNDRED, "abc", "7", params());
        assertThat(junk.getNumber()).isZero();
        assertThat(junk.getSize()).isEqualTo(ListView.DEFAULT_SIZE);
        ListView<Integer> beyond = ListView.of(HUNDRED, "999", "25", params());
        assertThat(beyond.getNumber()).isEqualTo(3);
        assertThat(beyond.isLast()).isTrue();
        ListView<Integer> empty = ListView.of(List.<Integer>of(), null, null, params());
        assertThat(empty.getFrom()).isZero();
        assertThat(empty.getTotalPages()).isEqualTo(1);
    }

    @Test void pageLinksShowNeighboursAndGaps() {
        ListView<Integer> v = ListView.of(HUNDRED, "5", "10", params());
        assertThat(v.getLinks()).extracting(ListView.PageLink::number).containsExactly(1, null, 3, 4, 5, 6, 7, null, 10);
        assertThat(v.getLinks()).filteredOn(ListView.PageLink::current).extracting(ListView.PageLink::number).containsExactly(5);
        assertThat(v.getLinks().get(0).url()).isEqualTo("?size=10");
    }

    @Test void chipsRemoveOneFilterAndResetThePage() {
        ListView<Integer> v = ListView.of(HUNDRED, "3", null, params("q", "db", "status", "disabled", "sort", ""))
                .chip("q", "Search: db").chip("status", "Disabled").chip("connectivity", "never shown");
        assertThat(v.getChips()).extracting(ListView.Chip::label).containsExactly("Search: db", "Disabled");
        assertThat(v.getChips().get(0).removeUrl()).isEqualTo("?status=disabled");
        assertThat(v.getChips().get(1).removeUrl()).isEqualTo("?q=db");
        assertThat(v.isFiltered()).isTrue();
        assertThat(v.getClearUrl()).isEqualTo("?");
    }
}
