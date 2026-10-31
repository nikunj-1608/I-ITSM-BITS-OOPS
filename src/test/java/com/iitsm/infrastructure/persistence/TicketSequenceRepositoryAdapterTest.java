package com.iitsm.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.repository.TicketSequenceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test of the persisted ticket counters against the Flyway-migrated H2 schema.
 * Every test uses its own department code / year so the shared in-memory database stays isolated.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketSequenceRepositoryAdapterTest {

    private static final int THREADS = 8;
    private static final int CALLS_PER_THREAD = 15;

    @Autowired
    private TicketSequenceRepository sequences;

    @Test
    @DisplayName("the first number is 1 and numbers then increase by one")
    void startsAtOneAndIncrements() {
        assertThat(sequences.next("TSA", 3001)).isEqualTo(1L);
        assertThat(sequences.next("TSA", 3001)).isEqualTo(2L);
        assertThat(sequences.next("TSA", 3001)).isEqualTo(3L);
    }

    @Test
    @DisplayName("different departments count independently")
    void departmentsAreIndependent() {
        assertThat(sequences.next("TSB", 3002)).isEqualTo(1L);
        assertThat(sequences.next("TSB", 3002)).isEqualTo(2L);
        assertThat(sequences.next("TSC", 3002)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a new year restarts at 1 without disturbing the previous year")
    void yearsAreIndependent() {
        assertThat(sequences.next("TSD", 3003)).isEqualTo(1L);
        assertThat(sequences.next("TSD", 3003)).isEqualTo(2L);
        assertThat(sequences.next("TSD", 3004)).isEqualTo(1L);
        assertThat(sequences.next("TSD", 3003)).isEqualTo(3L);
    }

    @Test
    @DisplayName("parallel callers get unique, gap-free numbers, including the race to create the first row")
    void parallelCallersGetUniqueNumbers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<List<Long>>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                workers.add(() -> {
                    List<Long> issued = new ArrayList<>();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        issued.add(sequences.next("TSE", 3005));
                    }
                    return issued;
                });
            }
            Set<Long> all = new TreeSet<>();
            int total = 0;
            for (Future<List<Long>> future : pool.invokeAll(workers, 60, TimeUnit.SECONDS)) {
                List<Long> issued = future.get();
                total += issued.size();
                all.addAll(issued);
            }
            assertThat(total).isEqualTo(THREADS * CALLS_PER_THREAD);
            assertThat(all).as("no duplicates").hasSize(total);
            assertThat(all.iterator().next()).isEqualTo(1L);
            assertThat(((TreeSet<Long>) all).last()).as("no gaps").isEqualTo((long) total);
        } finally {
            pool.shutdownNow();
        }
    }
}
