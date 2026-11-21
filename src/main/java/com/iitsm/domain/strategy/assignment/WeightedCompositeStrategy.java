package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

// OOP (Multiple interfaces): a class may realize several interfaces, unlike extending several classes.
/**
 * Pattern: Composite / Strategy.
 * Default intelligent routing strategy combining skills (0.50), load (0.30), and experience (0.20).
 * Dynamically tunes factor weights based on incident priority.
 *
 * <p>Since ADR-044 the factors are {@link AssignmentFactor} objects and the weights a {@link FactorWeights} table, so a
 * new factor is a new class registered with its weights (Open/Closed). The default factors and weights reproduce the
 * earlier inline calculation exactly (pinned by {@code AssignmentStrategyCharacterizationTest}). A ticket without a
 * priority is weighted as P3 ({@link #DEFAULT_PRIORITY}); ties keep the first candidate.
 *
 * <p>Phase L4 adds the optional fourth factor, {@link ServiceExperienceFactor}, through {@link #withServiceExperience}:
 * the three historic weights are scaled by {@code 1 - w} and the new factor gets {@code w}; with {@code w = 0} the result
 * is the historic strategy, bit for bit. The factor reads how many tickets of the ticket's IT service each candidate has
 * resolved from a {@link ServiceExperienceSource} port, one lookup per ranking, only when the factor is configured and
 * the ticket has a specific service. The strategy is {@link ServiceExperienceBindable} so the offline comparison can
 * replace that source.
 */
public class WeightedCompositeStrategy implements AssignmentStrategy, ServiceExperienceBindable {

    // OOP (static final): a named constant instead of a magic string.
    public static final String STRATEGY_NAME = "WEIGHTED_COMPOSITE";
    /** Weights used for a ticket that has no priority yet. */
    public static final Priority DEFAULT_PRIORITY = Priority.P3;
    /** The largest service-experience weight the application accepts: skills, load and experience stay in charge. */
    public static final double MAX_SERVICE_EXPERIENCE_WEIGHT = 0.5;

    private final List<AssignmentFactor> factors;
    private final FactorWeights weights;
    private final String name;
    private final ServiceExperienceSource experienceSource;
    private final boolean readsServiceExperience;

    // OOP (Constructor overloading): the no-argument constructor gives the defaults; the next one takes factors and weights.
    /** The default factors (skill match, load, experience scaled at 12 years) with the default weights. */
    public WeightedCompositeStrategy() {
        this(List.of(new SkillMatchFactor(), new LoadFactor(),
                new ExperienceFactor(ExperienceFactor.COMPOSITE_FULL_SCORE_YEARS)), FactorWeights.defaults());
    }

    /** Custom factors; {@code weights} must give every factor a weight for every priority. */
    public WeightedCompositeStrategy(List<AssignmentFactor> factors, FactorWeights weights) {
        this(factors, weights, STRATEGY_NAME, ServiceExperienceSource.none());
    }

    private WeightedCompositeStrategy(List<AssignmentFactor> factors, FactorWeights weights, String name,
            ServiceExperienceSource experienceSource) {
        this.factors = List.copyOf(Objects.requireNonNull(factors, "factors cannot be null"));
        this.weights = Objects.requireNonNull(weights, "weights cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.experienceSource = Objects.requireNonNull(experienceSource, "experienceSource cannot be null");
        if (this.factors.isEmpty()) {
            throw new IllegalArgumentException("at least one assignment factor is needed");
        }
        for (AssignmentFactor factor : this.factors) {
            if (!weights.covers(factor.key())) {
                throw new IllegalArgumentException("no weights for assignment factor " + factor.key());
            }
        }
        this.readsServiceExperience = this.factors.stream().anyMatch(f -> ServiceExperienceFactor.KEY.equals(f.key()));
    }

    // OOP (static factory method): a named way to build a configured object, clearer than another constructor overload.
    /**
     * The historic strategy plus {@link ServiceExperienceFactor} with weight {@code weight} (0 up to
     * {@value #MAX_SERVICE_EXPERIENCE_WEIGHT}); {@code fullScoreTickets} resolved tickets of the service give the full raw
     * score. A weight of 0 returns the plain historic strategy and never asks {@code source}.
     */
    public static WeightedCompositeStrategy withServiceExperience(ServiceExperienceSource source, double weight, int fullScoreTickets) {
        Objects.requireNonNull(source, "source cannot be null");
        if (!(weight >= 0.0) || weight > MAX_SERVICE_EXPERIENCE_WEIGHT) {
            throw new IllegalArgumentException("the service-experience weight must be from 0 to " + MAX_SERVICE_EXPERIENCE_WEIGHT);
        }
        if (weight == 0.0) {
            return new WeightedCompositeStrategy();
        }
        List<AssignmentFactor> factors = List.of(new SkillMatchFactor(), new LoadFactor(),
                new ExperienceFactor(ExperienceFactor.COMPOSITE_FULL_SCORE_YEARS), new ServiceExperienceFactor(fullScoreTickets));
        return new WeightedCompositeStrategy(factors, FactorWeights.defaults().withFactor(ServiceExperienceFactor.KEY, weight),
                STRATEGY_NAME, source);
    }

    /** The same strategy under another name, to compare variants side by side (for example in the offline comparison). */
    public WeightedCompositeStrategy named(String variantName) {
        return new WeightedCompositeStrategy(factors, weights, variantName, experienceSource);
    }

    @Override
    public AssignmentStrategy bindExperience(ServiceExperienceSource source) {
        return new WeightedCompositeStrategy(factors, weights, name, source);
    }

    // OOP (Method overriding): implements the interface method with this strategy's own scoring.
    @Override
    public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
        RankedCandidates ranking = score(incident, candidates);
        return ranking.toRecommendation(name, rationale(ranking.top()));
    }

    @Override
    public List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        return score(incident, candidates).scores();
    }

    private RankedCandidates score(Incident incident, List<Technician> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new TechnicianUnavailableException("No available technicians found for weighted composite assignment");
        }
        AssignmentContext context = contextFor(incident, candidates);
        Priority priority = context.priorityOr(DEFAULT_PRIORITY);
        List<RankedCandidates.Entry> entries = new ArrayList<>(candidates.size());
        for (Technician technician : candidates) {
            entries.add(scoreOne(context, priority, technician));
        }
        return RankedCandidates.rank(entries,
                Comparator.comparingDouble(RankedCandidates.Entry::total).reversed());
    }

    /** The ticket's context; with the service-experience factor configured, one lookup for all candidates. */
    private AssignmentContext contextFor(Incident incident, List<Technician> candidates) {
        AssignmentContext context = AssignmentContext.of(incident);
        if (!readsServiceExperience) {
            return context;
        }
        String serviceId = incident.getServiceId();
        ServiceExperience unfiltered = new ServiceExperience(serviceId, Map.of());
        if (!unfiltered.applies()) {
            return context;
        }
        List<String> ids = candidates.stream().map(Technician::getId).toList();
        Map<String, Integer> counts = experienceSource.resolvedTicketCounts(serviceId, ids);
        return context.withServiceExperience(new ServiceExperience(serviceId, counts));
    }

    private RankedCandidates.Entry scoreOne(AssignmentContext context, Priority priority, Technician technician) {
        List<FactorScore> scores = new ArrayList<>(factors.size());
        double total = 0.0;
        for (AssignmentFactor factor : factors) {
            AssignmentFactor.Reading reading = factor.read(context, technician);
            FactorScore score = FactorScore.of(factor.key(), reading.rawScore(),
                    weights.weightOf(priority, factor.key()), reading.detail());
            scores.add(score);
            total += score.contribution();
        }
        return new RankedCandidates.Entry(technician, scores, total);
    }

    private String rationale(RankedCandidates.Entry top) {
        StringJoiner parts = new StringJoiner(", ", "Composite score: " + String.format("%.2f", top.total()) + " (", ")");
        for (int i = 0; i < factors.size(); i++) {
            FactorScore score = top.factorScores().get(i);
            parts.add(factors.get(i).label() + ": " + String.format("%.2f * %.2f", score.rawScore(), score.weight()));
        }
        return parts.toString();
    }

    @Override
    public String getStrategyName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "Intelligent multi-factor routing balancing skills, current capacity, and experience, dynamically tuned by incident priority.";
    }
}
