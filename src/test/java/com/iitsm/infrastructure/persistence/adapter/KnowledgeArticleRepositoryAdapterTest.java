package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.AlreadyPromotedException;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.KnowledgeArticle;
import com.iitsm.domain.repository.KnowledgeArticleRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-042: knowledge articles on H2 through the adapter. Articles are created in 2099 so they are the newest of the
 * shared test database; every row of this class is deleted afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeArticleRepositoryAdapterTest {

    private static final String PREFIX = "KBT-";
    private static final Instant BASE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired private KnowledgeArticleRepository articles;
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
        jdbc.update("DELETE FROM knowledge_articles WHERE source_incident_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM incidents WHERE id LIKE ?", PREFIX + "%");
    }

    private KnowledgeArticle article(String suffix, IncidentCategory category, int minutesAfterBase) {
        String ticket = PREFIX + suffix;
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, category, status, created_at) "
                + "VALUES (?, 'Title', 'Description', 'EMP-DEMO-01', ?, 'CLOSED', ?)", ticket, category.name(), Timestamp.from(BASE));
        return new KnowledgeArticle(KnowledgeArticle.ID_PREFIX + ticket, "Article " + suffix, category, "Problem " + suffix,
                "Root " + suffix, "Steps " + suffix, "Prevent " + suffix, ticket, "TECH-NET-1",
                BASE.plusSeconds(60L * minutesAfterBase));
    }

    private List<String> ours(List<SuggestionCandidate> candidates) {
        return candidates.stream().map(SuggestionCandidate::sourceId).filter(id -> id.startsWith("KB-" + PREFIX)).toList();
    }

    @Test
    @DisplayName("An article is stored and read back with every field")
    void storesAndReads() {
        KnowledgeArticle saved = articles.save(article("A", IncidentCategory.STORAGE, 1));

        KnowledgeArticle read = articles.findById(saved.getId()).orElseThrow();
        assertThat(read.getTitle()).isEqualTo("Article A");
        assertThat(read.getCategory()).isEqualTo(IncidentCategory.STORAGE);
        assertThat(read.getRootCause()).isEqualTo("Root A");
        assertThat(read.getSourceIncidentId()).isEqualTo(PREFIX + "A");
        assertThat(read.getCreatedBy()).isEqualTo("TECH-NET-1");
        assertThat(articles.existsById(saved.getId())).isTrue();
        assertThat(articles.existsBySourceIncidentId(PREFIX + "A")).isTrue();
        assertThat(articles.existsById("KB-NOPE")).isFalse();
        assertThat(articles.existsBySourceIncidentId(PREFIX + "NOPE")).isFalse();
    }

    @Test
    @DisplayName("Saving an article whose id exists never overwrites it: 409 ERR_ALREADY_PROMOTED")
    void neverOverwrites() {
        KnowledgeArticle first = articles.save(article("A", IncidentCategory.STORAGE, 1));
        KnowledgeArticle again = new KnowledgeArticle(first.getId(), "Changed", IncidentCategory.STORAGE, "p", "r", "s", "a",
                first.getSourceIncidentId(), "ADMIN-SYS-01", BASE);

        assertThatThrownBy(() -> articles.save(again)).isInstanceOf(AlreadyPromotedException.class);
        assertThat(articles.findById(first.getId()).orElseThrow().getTitle()).isEqualTo("Article A");
    }

    @Test
    @DisplayName("The window is newest first, cut to the limit, by category or across all")
    void window() {
        articles.save(article("OLD", IncidentCategory.STORAGE, 1));
        articles.save(article("NEW", IncidentCategory.STORAGE, 3));
        articles.save(article("DB", IncidentCategory.DATABASE, 2));

        assertThat(ours(articles.findNewestArticles(IncidentCategory.STORAGE, 50)))
                .containsExactly("KB-" + PREFIX + "NEW", "KB-" + PREFIX + "OLD");
        assertThat(ours(articles.findNewestArticles(IncidentCategory.STORAGE, 1))).containsExactly("KB-" + PREFIX + "NEW");
        assertThat(ours(articles.findNewestArticles(null, 50)))
                .containsExactly("KB-" + PREFIX + "NEW", "KB-" + PREFIX + "DB", "KB-" + PREFIX + "OLD");
        assertThat(articles.findNewestArticles(IncidentCategory.STORAGE, 0)).isEmpty();
    }

    @Test
    @DisplayName("A candidate is a KB_ARTICLE whose origin is the source ticket")
    void candidateShape() {
        articles.save(article("A", IncidentCategory.STORAGE, 1));

        SuggestionCandidate candidate = articles.findNewestArticles(IncidentCategory.STORAGE, 1).get(0);
        assertThat(candidate.sourceType()).isEqualTo(SuggestionSourceType.KB_ARTICLE);
        assertThat(candidate.sourceId()).isEqualTo("KB-" + PREFIX + "A");
        assertThat(candidate.originIncidentId()).isEqualTo(PREFIX + "A");
        assertThat(candidate.priority()).isNull();
        assertThat(candidate.problemText()).isEqualTo("Problem A");
        assertThat(candidate.rootCause()).isEqualTo("Root A");
    }
}
