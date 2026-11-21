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
import com.iitsm.domain.strategy.classification.ServiceCorroboration;
import com.iitsm.domain.strategy.classification.ServiceMatcher;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Measures the production classifier and today's routing rule and records the numbers. Since Phase F it also asserts
 * the classification budget of decision D14 (held-out accuracy not lower, every audit example met, at most 3 of 36
 * held-out tickets in triage); the routing figure stays report-only until Phase G.
 *
 * <p>Runs on its own in-memory database ({@code benchmarkdb}), so the seed it needs never touches the database
 * the other integration tests share. The routing figure replays the intake rule (matched service, else the
 * general service, then that service's owning team) and compares the team with the team that handles the
 * ticket's <em>true</em> category.
 */
@Tag("benchmark")
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:benchmarkdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL")
@ActiveProfiles("test")
class ClassificationBenchmarkTest {

    private static final String GENERAL_SERVICE_ID = "SRV-GEN-001";
    private static final String REPORT_FILE = "classification-benchmark.txt";
    /** Phase G exit: at least this share of the auto-routed tickets reach the team of their true category. */
    private static final double ROUTING_TARGET = 0.95;

    @Autowired
    private IncidentClassifier classifier;

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

    /** One labelled ticket and what the classifier and today's routing rule did with it. */
    private record Outcome(BenchmarkData.LabelledTicket ticket, ClassificationResult result,
            Optional<ItService> matched, ItService routedService, boolean routedToCategoryTeam) {

        boolean triaged(double gate) {
            return result.confidence() < gate;
        }
    }

    @Test
    @DisplayName("Benchmark: held-out accuracy, triage rate and team match are recorded; accuracy and triage hold the D14 budget")
    void recordsClassificationAndRoutingBaseline() throws IOException {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        List<BenchmarkData.LabelledTicket> heldOut = BenchmarkData.heldOutTickets();
        List<BenchmarkData.LabelledTicket> audit = BenchmarkData.auditExamples();
        assertThat(heldOut).as("held-out tickets").hasSize(36);
        assertThat(audit).as("audit examples").isNotEmpty();

        List<Outcome> heldOutOutcomes = evaluate(heldOut);
        List<Outcome> auditOutcomes = evaluate(audit);
        List<String> report = new ArrayList<>();
        report.add("Classification benchmark (gate " + minConfidence + ")");
        report.addAll(summarise("held-out test_tickets.csv", heldOutOutcomes));
        report.addAll(summarise("audit_examples.tsv", auditOutcomes));
        Path written = BenchmarkReport.write(REPORT_FILE, report);
        assertThat(written).exists();

        // Phase G, ADR-036: the exit target of the routing rule, replayed through the real TicketRouter
        assertThat(routedToTrueCategoryTeam(auditOutcomes))
                .as("audit examples: auto-routed tickets that reach the team of their true category").isEqualTo(1.0);
        List<Outcome> everything = new ArrayList<>(heldOutOutcomes);
        everything.addAll(auditOutcomes);
        assertThat(routedToTrueCategoryTeam(everything))
                .as("held-out and audit tickets together: auto-routed tickets that reach the team of their true category")
                .isGreaterThanOrEqualTo(ROUTING_TARGET);

        // Phase F, decision D14 (ADR-034): the same budget as ClassificationBudgetTest, through the Spring wiring
        assertThat(heldOutOutcomes.stream().filter(this::meetsExpectation).count())
                .as("held-out tickets met").isGreaterThanOrEqualTo(ClassificationBudgetTest.HELD_OUT_CORRECT_BASELINE);
        assertThat(heldOutOutcomes.stream().filter(o -> o.triaged(minConfidence)).count())
                .as("held-out tickets in triage").isLessThanOrEqualTo(ClassificationBudgetTest.HELD_OUT_TRIAGE_BUDGET);
        assertThat(auditOutcomes.stream().filter(o -> !meetsExpectation(o)).map(o -> o.ticket().id()))
                .as("audit examples missed").isEmpty();
    }

    /** The share of auto-routed (not in triage) labelled tickets that reach the team of their true category. */
    private double routedToTrueCategoryTeam(List<Outcome> outcomes) {
        List<Outcome> routed = outcomes.stream()
                .filter(o -> !o.triaged(minConfidence) && o.ticket().expectedCategory().isPresent())
                .collect(Collectors.toList());
        return routed.isEmpty() ? 1.0 : (double) routed.stream().filter(Outcome::routedToCategoryTeam).count() / routed.size();
    }

    private List<Outcome> evaluate(List<BenchmarkData.LabelledTicket> tickets) {
        List<ItService> services = itServiceRepository.findAll();
        Map<String, SupportTeam> teams = supportTeamRepository.findAll().stream()
                .collect(Collectors.toMap(SupportTeam::getId, Function.identity()));
        ItService general = itServiceRepository.findById(GENERAL_SERVICE_ID).orElseThrow();
        List<Outcome> outcomes = new ArrayList<>();
        for (BenchmarkData.LabelledTicket ticket : tickets) {
            ClassificationResult classified = classifier.classify(ticket.title(), ticket.description());
            Optional<ItService> matched = serviceMatcher.findBestMatch(ticket.title(), ticket.description(), services);
            // Same steps as IncidentIntakePipeline (ADR-052, Phase 5): a matching service can corroborate
            ClassificationResult result = ServiceCorroboration.apply(
                    classified, matched, ticketRouter.owningTeamId(classified.category(), null));
            ItService routed = matched.orElse(general);
            // The team is the one that handles the classified category (ADR-036); the service only carries criticality
            SupportTeam team = ticketRouter.owningTeamId(result.category(), routed).map(teams::get).orElse(null);
            boolean categoryTeam = ticket.expectedCategory().isPresent() && team != null
                    && team.getHandledCategories().contains(ticket.expectedCategory().get());
            outcomes.add(new Outcome(ticket, result, matched, routed, categoryTeam));
        }
        return outcomes;
    }

    private List<String> summarise(String dataset, List<Outcome> outcomes) {
        List<String> lines = new ArrayList<>();
        long correct = outcomes.stream().filter(this::meetsExpectation).count();
        long triaged = outcomes.stream().filter(o -> o.triaged(minConfidence)).count();
        List<Outcome> routedTickets = outcomes.stream()
                .filter(o -> !o.triaged(minConfidence) && o.ticket().expectedCategory().isPresent())
                .collect(Collectors.toList());
        long toCategoryTeam = routedTickets.stream().filter(Outcome::routedToCategoryTeam).count();
        long fallback = outcomes.stream().filter(o -> o.matched().isEmpty()).count();
        lines.add("");
        lines.add("== " + dataset + " (" + outcomes.size() + " tickets)");
        lines.add(String.format("meets expectation      : %d/%d", correct, outcomes.size()));
        lines.add(String.format("sent to triage         : %d/%d", triaged, outcomes.size()));
        lines.add(String.format("auto-routed to the team of the true category (the classified category decides): %d/%d", toCategoryTeam, routedTickets.size()));
        lines.add(String.format("no service matched (general service fallback): %d/%d", fallback, outcomes.size()));
        outcomes.forEach(o -> lines.add(describe(o)));
        return lines;
    }

    /** A labelled category must be predicted (any confidence); a TRIAGE label must end below the gate. */
    private boolean meetsExpectation(Outcome outcome) {
        return outcome.ticket().isMetBy(outcome.result().category(), outcome.triaged(minConfidence));
    }

    private String describe(Outcome o) {
        String expected = o.ticket().expectedCategory().map(IncidentCategory::name).orElse("TRIAGE");
        return String.format("  %s %-6s expected=%-14s got=%-14s conf=%.3f%s service=%s team-ok=%s | %s",
                meetsExpectation(o) ? "ok  " : "MISS", o.ticket().id(), expected, o.result().category(),
                o.result().confidence(), o.triaged(minConfidence) ? " (triage)" : "",
                o.routedService().getId(), o.routedToCategoryTeam(), o.ticket().title());
    }
}
