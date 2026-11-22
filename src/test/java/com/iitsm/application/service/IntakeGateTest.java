package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ServiceBusyException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-018: one ticket intake at a time, first come first served, with a limit on how long a report waits. */
class IntakeGateTest {

    private static final long LONG_WAIT_MS = 10_000;

    @Test
    @DisplayName("Never more than one piece of work runs at a time, however many threads compete")
    void runsOneAtATime() throws Exception {
        IntakeGate gate = new IntakeGate(true, LONG_WAIT_MS);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                futures.add(pool.submit(() -> gate.runExclusive(() -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    sleep(15);
                    active.decrementAndGet();
                    return id;
                })));
            }
            for (Future<Integer> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxActive.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Waiting reports are served in arrival order")
    void firstComeFirstServed() throws Exception {
        IntakeGate gate = new IntakeGate(true, LONG_WAIT_MS);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = pool.submit(() -> gate.runExclusive(() -> {
                firstInside.countDown();
                await(releaseFirst);
                order.add("A");
                return null;
            }));
            assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> gate.runExclusive(() -> order.add("B")));
            waitUntilWaiting(gate, 1);
            Future<?> third = pool.submit(() -> gate.runExclusive(() -> order.add("C")));
            waitUntilWaiting(gate, 2);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(order).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("A report that cannot get its turn within the limit is told the system is busy")
    void timeoutAnswersBusy() throws Exception {
        IntakeGate gate = new IntakeGate(true, 150);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> gate.runExclusive(() -> {
                inside.countDown();
                await(release);
                return null;
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> gate.runExclusive(() -> "never runs")).isInstanceOf(ServiceBusyException.class);
            assertThat(gate.getWaitingCount()).as("the timed-out report left the queue").isZero();

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("The gate is released when the work fails, so the next report is not blocked")
    void releasedAfterFailure() {
        IntakeGate gate = new IntakeGate(true, LONG_WAIT_MS);

        assertThatThrownBy(() -> gate.runExclusive(() -> {
            throw new IllegalStateException("intake failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(gate.runExclusive(() -> "next report")).isEqualTo("next report");
    }

    @Test
    @DisplayName("A waiting report that is interrupted gives up with a busy answer and keeps the interrupt flag")
    void interruptedWaiterGivesUp() throws Exception {
        IntakeGate gate = new IntakeGate(true, LONG_WAIT_MS);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> gate.runExclusive(() -> {
                inside.countDown();
                await(release);
                return null;
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicInteger interruptedFlag = new AtomicInteger();
            Future<Object> waiter = pool.submit(() -> {
                try {
                    return gate.runExclusive(() -> "never runs");
                } catch (ServiceBusyException ex) {
                    interruptedFlag.set(Thread.currentThread().isInterrupted() ? 1 : 0);
                    return ex;
                }
            });
            waitUntilWaiting(gate, 1);
            pool.shutdownNow();

            assertThat(waiter.get(5, TimeUnit.SECONDS)).isInstanceOf(ServiceBusyException.class);
            assertThat(interruptedFlag.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("With serialisation switched off, two reports run at the same time")
    void switchedOffRunsConcurrently() throws Exception {
        IntakeGate gate = new IntakeGate(false, LONG_WAIT_MS);
        CountDownLatch bothInside = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> gate.runExclusive(() -> {
                    bothInside.countDown();
                    return await(bothInside);
                })));
            }
            for (Future<Boolean> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).as("both were inside at once").isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A non-positive wait limit is rejected")
    void invalidLimitIsRejected() {
        assertThatThrownBy(() -> new IntakeGate(true, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntilWaiting(IntakeGate gate, int expected) throws InterruptedException {
        for (int i = 0; i < 500 && gate.getWaitingCount() < expected; i++) {
            Thread.sleep(10);
        }
        assertThat(gate.getWaitingCount()).isEqualTo(expected);
    }
}
