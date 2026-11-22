package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.RefreshToken;
import com.iitsm.domain.repository.RefreshTokenRepository;
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

/** ADR-047: refresh tokens on H2, through the adapter; the rows of this class are deleted afterwards. */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenRepositoryAdapterTest {

    private static final String USER = "EMP-DEMO-01";
    private static final String OTHER_USER = "TECH-NET-1";
    private static final String PREFIX = "rta-";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(8 * 3600);

    @Autowired private RefreshTokenRepository tokens;
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
        jdbc.update("DELETE FROM refresh_tokens WHERE family_id LIKE ?", PREFIX + "%");
    }

    private RefreshToken store(String user, String family, String hash, Instant issuedAt, Instant expiresAt) {
        return tokens.save(RefreshToken.issue(user, PREFIX + family, hash, 1, issuedAt, expiresAt));
    }

    private static String hash(String seed) {
        return (PREFIX + seed + "0".repeat(64)).substring(0, 64);
    }

    @Test
    @DisplayName("a token is stored with an id and read back by its hash, with every field")
    void storesAndFinds() {
        RefreshToken saved = store(USER, "f1", hash("a"), NOW, EXPIRES);

        RefreshToken found = tokens.findByTokenHash(hash("a")).orElseThrow();
        assertThat(saved.id()).isNotNull();
        assertThat(found).isEqualTo(saved);
        assertThat(found.userId()).isEqualTo(USER);
        assertThat(found.familyId()).isEqualTo(PREFIX + "f1");
        assertThat(found.tokenVersion()).isEqualTo(1);
        assertThat(found.expiresAt()).isEqualTo(EXPIRES);
        assertThat(found.isRevoked()).isFalse();
        assertThat(tokens.findByTokenHash(hash("missing"))).isEmpty();
    }

    @Test
    @DisplayName("the database holds the hash passed in and nothing else that identifies the token")
    void storesOnlyTheHash() {
        store(USER, "f1", hash("a"), NOW, EXPIRES);

        List<String> columns = jdbc.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE family_id = ?", String.class, PREFIX + "f1");
        assertThat(columns).containsExactly(hash("a"));
    }

    @Test
    @DisplayName("a family is active while it has an unrevoked, unexpired token")
    void familyActivity() {
        store(USER, "f1", hash("a"), NOW, EXPIRES);

        assertThat(tokens.isFamilyActive(PREFIX + "f1", NOW)).isTrue();
        assertThat(tokens.isFamilyActive(PREFIX + "f1", EXPIRES.minusNanos(1_000))).isTrue();
        assertThat(tokens.isFamilyActive(PREFIX + "f1", EXPIRES)).isFalse();
        assertThat(tokens.isFamilyActive(PREFIX + "other", NOW)).isFalse();
    }

    @Test
    @DisplayName("claiming a token for rotation works once; the second claim loses and the reason stays ROTATED")
    void claimWorksOnce() {
        RefreshToken saved = store(USER, "f1", hash("a"), NOW, EXPIRES);

        assertThat(tokens.claimForRotation(saved.id(), NOW)).isTrue();
        assertThat(tokens.claimForRotation(saved.id(), NOW.plusSeconds(5))).isFalse();

        RefreshToken after = tokens.findByTokenHash(hash("a")).orElseThrow();
        assertThat(after.wasRotated()).isTrue();
        assertThat(after.revokedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("200 threads claiming one token: exactly one wins")
    void concurrentClaim() throws Exception {
        RefreshToken saved = store(USER, "f1", hash("a"), NOW, EXPIRES);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tokens.claimForRotation(saved.id(), NOW);
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
    @DisplayName("the replacement is linked to the rotated token")
    void replacement() {
        RefreshToken first = store(USER, "f1", hash("a"), NOW, EXPIRES);
        RefreshToken second = store(USER, "f1", hash("b"), NOW.plusSeconds(60), EXPIRES);
        tokens.claimForRotation(first.id(), NOW.plusSeconds(60));

        tokens.markReplacedBy(first.id(), second.id());

        assertThat(tokens.findByTokenHash(hash("a")).orElseThrow().replacedById()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("revoking a family leaves other families and keeps the reason an earlier revocation set")
    void revokeFamily() {
        RefreshToken rotated = store(USER, "f1", hash("a"), NOW, EXPIRES);
        store(USER, "f1", hash("b"), NOW.plusSeconds(60), EXPIRES);
        store(USER, "f2", hash("c"), NOW, EXPIRES);
        tokens.claimForRotation(rotated.id(), NOW.plusSeconds(60));

        tokens.revokeFamily(PREFIX + "f1", "LOGOUT", NOW.plusSeconds(120));

        assertThat(tokens.findByTokenHash(hash("a")).orElseThrow().revokedReason()).isEqualTo("ROTATED");
        assertThat(tokens.findByTokenHash(hash("b")).orElseThrow().revokedReason()).isEqualTo("LOGOUT");
        assertThat(tokens.isFamilyActive(PREFIX + "f1", NOW)).isFalse();
        assertThat(tokens.isFamilyActive(PREFIX + "f2", NOW)).isTrue();
    }

    @Test
    @DisplayName("revoking all tokens of a user leaves other users alone")
    void revokeAllForUser() {
        store(USER, "f1", hash("a"), NOW, EXPIRES);
        store(USER, "f2", hash("b"), NOW, EXPIRES);
        store(OTHER_USER, "f3", hash("c"), NOW, EXPIRES);

        tokens.revokeAllForUser(USER, "LOGOUT_ALL", NOW);

        assertThat(tokens.isFamilyActive(PREFIX + "f1", NOW)).isFalse();
        assertThat(tokens.isFamilyActive(PREFIX + "f2", NOW)).isFalse();
        assertThat(tokens.isFamilyActive(PREFIX + "f3", NOW)).isTrue();
    }

    @Test
    @DisplayName("live sessions are listed oldest first, once per family, without revoked or expired ones")
    void activeFamilies() {
        store(USER, "newer", hash("a"), NOW.plusSeconds(100), EXPIRES);
        store(USER, "older", hash("b"), NOW, EXPIRES);
        store(USER, "dead", hash("c"), NOW, EXPIRES);
        store(USER, "gone", hash("d"), NOW.minusSeconds(100), NOW.plusSeconds(10));
        tokens.revokeFamily(PREFIX + "dead", "LOGOUT", NOW);

        List<String> live = tokens.activeFamiliesOf(USER, NOW.plusSeconds(20)).stream()
                .filter(family -> family.startsWith(PREFIX)).toList();

        assertThat(live).containsExactly(PREFIX + "older", PREFIX + "newer");
    }

    @Test
    @DisplayName("purging deletes tokens that expired before the cutoff and keeps the rest")
    void purge() {
        store(USER, "old", hash("a"), NOW.minusSeconds(1000), NOW.minusSeconds(500));
        store(USER, "recent", hash("b"), NOW.minusSeconds(1000), NOW.minusSeconds(5));
        store(USER, "live", hash("c"), NOW, EXPIRES);

        int deleted = tokens.deleteExpiredBefore(NOW.minusSeconds(100));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(tokens.findByTokenHash(hash("a"))).isEmpty();
        assertThat(tokens.findByTokenHash(hash("b"))).isPresent();
        assertThat(tokens.findByTokenHash(hash("c"))).isPresent();
    }
}
