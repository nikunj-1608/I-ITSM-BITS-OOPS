package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.MfaRecoveryCode;
import com.iitsm.domain.repository.MfaRecoveryCodeRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** ADR-048: recovery codes on H2, through the adapter; the rows of this class are deleted afterwards. */
@SpringBootTest
@ActiveProfiles("test")
class MfaRecoveryCodeRepositoryAdapterTest {

    private static final String USER = "TECH-NET-1";
    private static final String OTHER_USER = "TECH-NET-2";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired private MfaRecoveryCodeRepository codes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM mfa_recovery_codes WHERE user_id IN (?, ?)", USER, OTHER_USER);
    }

    @Test
    @DisplayName("codes are stored as given (hashes), oldest first, with no use time")
    void storesAndReads() {
        codes.replaceAll(USER, List.of("hash-1", "hash-2", "hash-3"), NOW);

        List<MfaRecoveryCode> unused = codes.findUnused(USER);

        assertThat(unused).extracting(MfaRecoveryCode::codeHash).containsExactly("hash-1", "hash-2", "hash-3");
        assertThat(unused).allSatisfy(code -> {
            assertThat(code.id()).isNotNull();
            assertThat(code.userId()).isEqualTo(USER);
            assertThat(code.isUsed()).isFalse();
            assertThat(code.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("replacing deletes the earlier codes and keeps other users' codes")
    void replaceAll() {
        codes.replaceAll(USER, List.of("old-1", "old-2"), NOW);
        codes.replaceAll(OTHER_USER, List.of("other-1"), NOW);

        codes.replaceAll(USER, List.of("new-1"), NOW.plusSeconds(60));

        assertThat(codes.findUnused(USER)).extracting(MfaRecoveryCode::codeHash).containsExactly("new-1");
        assertThat(codes.findUnused(OTHER_USER)).extracting(MfaRecoveryCode::codeHash).containsExactly("other-1");
    }

    @Test
    @DisplayName("a used code leaves the unused list, and can be used only once")
    void markUsedOnce() {
        codes.replaceAll(USER, List.of("hash-1", "hash-2"), NOW);
        long first = codes.findUnused(USER).get(0).id();

        assertThat(codes.markUsed(first, NOW)).isTrue();
        assertThat(codes.markUsed(first, NOW.plusSeconds(5))).isFalse();

        assertThat(codes.findUnused(USER)).extracting(MfaRecoveryCode::codeHash).containsExactly("hash-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NOT NULL",
                Integer.class, USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("200 threads using one code: exactly one wins")
    void concurrentUse() throws Exception {
        codes.replaceAll(USER, List.of("hash-1"), NOW);
        long id = codes.findUnused(USER).get(0).id();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return codes.markUsed(id, NOW);
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> result : results) {
            winners += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting removes one user's codes only")
    void deleteAll() {
        codes.replaceAll(USER, List.of("hash-1"), NOW);
        codes.replaceAll(OTHER_USER, List.of("other-1"), NOW);

        codes.deleteAll(USER);

        assertThat(codes.findUnused(USER)).isEmpty();
        assertThat(codes.findUnused(OTHER_USER)).hasSize(1);
    }
}
