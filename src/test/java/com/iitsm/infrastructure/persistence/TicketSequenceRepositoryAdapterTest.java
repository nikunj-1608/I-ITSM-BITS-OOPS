package com.iitsm.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test of the persisted ticket counters against the Flyway-migrated H2 schema.
 * Every test uses its own department code / year so the shared in-memory database stays isolated.
 * Each reservation runs in its own committed transaction, like one ticket intake does.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketSequenceRepositoryAdapterTest {

    private static final int THREADS = 8;
    private static final int CALLS_PER_THREAD = 15;

    @Autowired
    private TicketSequenceRepository sequences;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long next(String code, int year) {
        return new TransactionTemplate(transactionManager).execute(status -> sequences.next(code, year));
    }

    @Test
    @DisplayName("the first number is 1 and numbers then increase by one")
    void startsAtOneAndIncrements() {
        assertThat(next("TSA", 3001)).isEqualTo(1L);
        assertThat(next("TSA", 3001)).isEqualTo(2L);
        assertThat(next("TSA", 3001)).isEqualTo(3L);
    }

    @Test
    @DisplayName("different departments count independently")
    void departmentsAreIndependent() {
        assertThat(next("TSB", 3002)).isEqualTo(1L);
        assertThat(next("TSB", 3002)).isEqualTo(2L);
        assertThat(next("TSC", 3002)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a new year restarts at 1 without disturbing the previous year")
    void yearsAreIndependent() {
        assertThat(next("TSD", 3003)).isEqualTo(1L);
        assertThat(next("TSD", 3003)).isEqualTo(2L);
        assertThat(next("TSD", 3004)).isEqualTo(1L);
        assertThat(next("TSD", 3003)).isEqualTo(3L);
    }

    @Test
    @DisplayName("a rolled-back transaction gives its number back, so the sequence has no gap")
    void rolledBackNumberIsReused() {
        assertThat(next("TSF", 3006)).isEqualTo(1L);

        TransactionTemplate failing = new TransactionTemplate(transactionManager);
        long reservedThenRolledBack = failing.execute(status -> {
            long number = sequences.next("TSF", 3006);
            status.setRollbackOnly();
            return number;
        });
        assertThat(reservedThenRolledBack).isEqualTo(2L);

        assertThat(next("TSF", 3006)).as("number 2 is handed out again").isEqualTo(2L);
        assertThat(next("TSF", 3006)).isEqualTo(3L);
    }

    @Test
    @DisplayName("the very first number of a department/year is also given back on rollback")
    void firstNumberIsReusedAfterRollback() {
        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(sequences.next("TSG", 3007)).isEqualTo(1L);
            status.setRollbackOnly();
            return null;
        });
        assertThat(next("TSG", 3007)).isEqualTo(1L);
    }

    @Test
    @DisplayName("calling outside a transaction is refused instead of silently racing")
    void requiresATransaction() {
        assertThatThrownBy(() -> sequences.next("TSH", 3008))
                .isInstanceOf(IllegalTransactionStateException.class);
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
                        issued.add(next("TSE", 3005));
                    }
                    return issued;
                });
            }
            TreeSet<Long> all = new TreeSet<>();
            int total = 0;
            for (Future<List<Long>> future : pool.invokeAll(workers, 60, TimeUnit.SECONDS)) {
                List<Long> issued = future.get();
                total += issued.size();
                all.addAll(issued);
            }
            assertThat(total).isEqualTo(THREADS * CALLS_PER_THREAD);
            assertThat((Set<Long>) all).as("no duplicates").hasSize(total);
            assertThat(all.first()).isEqualTo(1L);
            assertThat(all.last()).as("no gaps").isEqualTo((long) total);
        } finally {
            pool.shutdownNow();
        }
    }
}
