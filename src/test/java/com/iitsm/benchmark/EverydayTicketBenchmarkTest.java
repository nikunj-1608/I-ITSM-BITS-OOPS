package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.TicketRouter;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.classification.KeywordClassifier;
import com.iitsm.domain.strategy.classification.NaiveBayesClassifier;
import com.iitsm.domain.strategy.classification.ServiceCorroboration;
import com.iitsm.domain.strategy.classification.ServiceMatcher;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-052, Phase 1: measures how the production classifier and routing rule treat short, plain-language tickets
 * ({@code classification/everyday_tickets.tsv}) and records it in {@code target/benchmarks/everyday-tickets.txt}.
 *
 * <p>The finding it measures (live run of 2026-11-21): 8 of 24 clear everyday tickets were not assigned automatically.
 * The assignment code was correct; each ticket stayed below the classification gate (ADR-009) and waited in the
 * administrator's triage queue, which the dispatcher deliberately skips.
 *
 * <p>Each ticket gets one {@link Verdict}. Success for a labelled category is measured by team, because the team decides
 * who works on the ticket (ADR-036). Until the fix phases land the test only asserts that nothing is worse than the
 * Phase 1 baseline; Phase 6 adds the target (at least 90 % of the clear tickets auto-routed to the right team, none to
 * a wrong team). Same Spring context and database ({@code benchmarkdb}) as {@link ClassificationBenchmarkTest}.
 */
@Tag("benchmark")
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:benchmarkdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL")
@ActiveProfiles("test")
class EverydayTicketBenchmarkTest {

    private static final String REPORT_FILE = "everyday-tickets.txt";
    private static final String VALIDATION_REPORT_FILE = "everyday-validation.txt";
    private static final int TICKET_COUNT = 42;
    private static final int VALIDATION_TICKET_COUNT = 24;

    /**
     * Clear tickets auto-routed to the team of their expected category: 21 at the Phase 1 baseline (2026-11-21), 24 after
     * Phase 2 (lexicon gaps: E01 printer, E13 unreachable, E23 account locked), 29 after Phase 3 (fusion rules: E02, E18,
     * E19, E27, E33), 31 after Phase 5 (the matched service corroborates: E11, E29). The ADR-052 target of 90 % (32) was
     * not reached: the four tickets still in triage (E08 webcam, E12 internet, E17 Teams, E32 AWS) each have one keyword
     * and a wrong, weak or security-flagged Naive Bayes answer.
     */
    static final int RIGHT_TEAM_BASELINE = 31;
    /** Clear tickets auto-routed to another team (the worst outcome): 2 until Phase 3 (E11 wifi, E32 AWS), then 0. */
    static final int WRONG_TEAM_BASELINE = 0;
    /** Tickets labelled TRIAGE (vague or not IT) that were auto-routed anyway: 0 since Phase 1. */
    static final int LEAKED_BASELINE = 0;

    /**
     * The validation set ({@code everyday_validation.tsv}, written before the corpus was touched and not used to choose a
     * word or a rule): on the code before ADR-052 11 of 19 clear tickets reached the right team, 2 a wrong team, 0
     * leaked; after the fix 14, 1 (V14, a lost laptop classified HARDWARE) and 0. The floors are the measured values.
     */
    static final int VALIDATION_RIGHT_TEAM_FLOOR = 14;
    static final int VALIDATION_WRONG_TEAM_FLOOR = 1;
    static final int VALIDATION_LEAKED_FLOOR = 0;

    /** What happened to one ticket. */
    enum Verdict {
        /** A clear ticket auto-routed to the team that handles its expected category: the goal. */
        AUTO_RIGHT_TEAM,
        /** A clear ticket left in triage: no technician until an administrator acts (the reported problem). */
        MISSED_TO_TRIAGE,
        /** A clear ticket auto-routed to a team that does not handle its expected category. */
        AUTO_WRONG_TEAM,
        /** A vague, non-IT or mixed ticket left for a person: correct. */
        KEPT_FOR_PERSON,
        /** A ticket labelled TRIAGE that was auto-routed anyway. */
        LEAKED
    }

    @Autowired
    private IncidentClassifier classifier;

    @Autowired
    private KeywordClassifier keywordClassifier;

    @Autowired
    private NaiveBayesClassifier naiveBayesClassifier;

    @Autowired
    private ServiceMatcher serviceMatcher;

    @Autowired
    private TicketRouter ticketRouter;

    @Autowired
    private ItServiceRepository itServiceRepository;

    @Autowired
    private SupportTeamRepository supportTeamRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanySeeder companySeeder;

    @Value("${iitsm.classification.min-confidence}")
    private double minConfidence;

    private record Outcome(BenchmarkData.LabelledTicket ticket, ClassificationResult result,
            ClassificationResult keyword, ClassificationResult naiveBayes, String teamId, ItService service,
            Verdict verdict) {}

    @Test
    @DisplayName("Benchmark: everyday tickets are recorded and no verdict count is worse than the floor of the last phase")
    void recordsEverydayTicketBaseline() throws IOException {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        List<BenchmarkData.LabelledTicket> tickets = BenchmarkData.everydayTickets();
        assertThat(tickets).as("everyday tickets").hasSize(TICKET_COUNT);

        List<Outcome> outcomes = evaluate(tickets);
        Map<Verdict, Long> counts = count(outcomes);
        Path written = BenchmarkReport.write(REPORT_FILE, report("Everyday ticket benchmark", outcomes, counts));
        assertThat(written).exists();

        assertThat(counts.get(Verdict.AUTO_RIGHT_TEAM)).as("clear tickets auto-routed to the right team")
                .isGreaterThanOrEqualTo(RIGHT_TEAM_BASELINE);
        assertThat(counts.get(Verdict.AUTO_WRONG_TEAM)).as("clear tickets auto-routed to a wrong team")
                .isLessThanOrEqualTo(WRONG_TEAM_BASELINE);
        assertThat(counts.get(Verdict.LEAKED)).as("vague or non-IT tickets auto-routed")
                .isLessThanOrEqualTo(LEAKED_BASELINE);
    }

    @Test
    @DisplayName("Benchmark: the everyday validation tickets are recorded and are not worse than the floor of the fix")
    void recordsEverydayValidationTickets() throws IOException {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        List<BenchmarkData.LabelledTicket> tickets = BenchmarkData.everydayValidationTickets();
        assertThat(tickets).as("everyday validation tickets").hasSize(VALIDATION_TICKET_COUNT);

        List<Outcome> outcomes = evaluate(tickets);
        Map<Verdict, Long> counts = count(outcomes);
        Path written = BenchmarkReport.write(VALIDATION_REPORT_FILE,
                report("Everyday validation tickets (not used for tuning)", outcomes, counts));
        assertThat(written).exists();

        assertThat(counts.get(Verdict.AUTO_RIGHT_TEAM)).as("validation: clear tickets auto-routed to the right team")
                .isGreaterThanOrEqualTo(VALIDATION_RIGHT_TEAM_FLOOR);
        assertThat(counts.get(Verdict.AUTO_WRONG_TEAM)).as("validation: clear tickets auto-routed to a wrong team")
                .isLessThanOrEqualTo(VALIDATION_WRONG_TEAM_FLOOR);
        assertThat(counts.get(Verdict.LEAKED)).as("validation: vague or non-IT tickets auto-routed")
                .isLessThanOrEqualTo(VALIDATION_LEAKED_FLOOR);
    }

    private List<Outcome> evaluate(List<BenchmarkData.LabelledTicket> tickets) {
        List<ItService> services = itServiceRepository.findAll();
        Map<String, SupportTeam> teams = supportTeamRepository.findAll().stream()
                .collect(Collectors.toMap(SupportTeam::getId, Function.identity()));
        ItService general = itServiceRepository.findById(ItService.GENERAL_SERVICE_ID).orElseThrow();
        List<Outcome> outcomes = new ArrayList<>();
        for (BenchmarkData.LabelledTicket t : tickets) {
            ClassificationResult classified = classifier.classify(t.title(), t.description());
            Optional<ItService> matched = serviceMatcher.findBestMatch(t.title(), t.description(), services);
            // Same steps as IncidentIntakePipeline: classify, match the service, let a matching service corroborate
            ClassificationResult result = ServiceCorroboration.apply(
                    classified, matched, ticketRouter.owningTeamId(classified.category(), null));
            ItService service = matched.orElse(general);
            String teamId = ticketRouter.owningTeamId(result.category(), service).orElse(null);
            SupportTeam team = teamId == null ? null : teams.get(teamId);
            outcomes.add(new Outcome(t, result, keywordClassifier.classify(t.title(), t.description()),
                    naiveBayesClassifier.classify(t.title(), t.description()), teamId, service,
                    verdict(t, result.confidence() < minConfidence, team)));
        }
        return outcomes;
    }

    private static Verdict verdict(BenchmarkData.LabelledTicket ticket, boolean triaged, SupportTeam team) {
        if (ticket.expectedCategory().isEmpty()) {
            return triaged ? Verdict.KEPT_FOR_PERSON : Verdict.LEAKED;
        }
        if (triaged) {
            return ticket.orTriage() ? Verdict.KEPT_FOR_PERSON : Verdict.MISSED_TO_TRIAGE;
        }
        boolean rightTeam = team != null && team.getHandledCategories().contains(ticket.expectedCategory().get());
        return rightTeam ? Verdict.AUTO_RIGHT_TEAM : Verdict.AUTO_WRONG_TEAM;
    }

    private static Map<Verdict, Long> count(List<Outcome> outcomes) {
        Map<Verdict, Long> counts = new EnumMap<>(Verdict.class);
        for (Verdict v : Verdict.values()) {
            counts.put(v, outcomes.stream().filter(o -> o.verdict() == v).count());
        }
        return counts;
    }

    private List<String> report(String heading, List<Outcome> outcomes, Map<Verdict, Long> counts) {
        long clear = outcomes.stream()
                .filter(o -> o.ticket().expectedCategory().isPresent() && !o.ticket().orTriage()).count();
        long forPerson = outcomes.size() - clear;
        long exactCategory = outcomes.stream().filter(o -> o.verdict() == Verdict.AUTO_RIGHT_TEAM
                && o.ticket().expectedCategory().get() == o.result().category()).count();
        List<String> lines = new ArrayList<>();
        lines.add(heading + " (ADR-052; gate " + minConfidence + ")");
        lines.add(String.format("clear tickets auto-routed to the right team : %d/%d (%.1f %%), exact category %d",
                counts.get(Verdict.AUTO_RIGHT_TEAM), clear, 100.0 * counts.get(Verdict.AUTO_RIGHT_TEAM) / clear,
                exactCategory));
        lines.add(String.format("clear tickets left in triage                : %d/%d", counts.get(Verdict.MISSED_TO_TRIAGE), clear));
        lines.add(String.format("clear tickets auto-routed to a wrong team   : %d/%d", counts.get(Verdict.AUTO_WRONG_TEAM), clear));
        lines.add(String.format("vague, non-IT or mixed kept for a person    : %d/%d", counts.get(Verdict.KEPT_FOR_PERSON), forPerson));
        lines.add(String.format("vague or non-IT auto-routed anyway          : %d/%d", counts.get(Verdict.LEAKED), forPerson));
        lines.add("");
        outcomes.forEach(o -> lines.add(describe(o)));
        return lines;
    }

    private static String describe(Outcome o) {
        String expected = o.ticket().expectedCategory().map(IncidentCategory::name).orElse("TRIAGE")
                + (o.ticket().orTriage() ? "|TRIAGE" : "");
        return String.format("  %-16s %s expected=%-21s got=%-14s conf=%.3f team=%-10s kw=%s %.2f nb=%s %.2f "
                        + "service=%s (%s) | %s",
                o.verdict(), o.ticket().id(), expected, o.result().category(), o.result().confidence(), o.teamId(),
                o.keyword().category(), o.keyword().confidence(), o.naiveBayes().category(), o.naiveBayes().confidence(),
                o.service().getId(), o.service().getOwningTeamId(), o.ticket().title());
    }
}
