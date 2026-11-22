package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// OOP (Composition + Open/Closed): holds strategies by name; adding a new one means writing a class and calling register(...), nothing else changes.
/**
 * Pattern: Registry / Strategy.
 * Thread-safe registry managing available technician assignment strategies and active routing policy.
 */
public class AssignmentStrategyRegistry {

    private final Map<String, AssignmentStrategy> strategies = new ConcurrentHashMap<>();
    private volatile String activeStrategyName;

    public AssignmentStrategyRegistry() {
        this(new WeightedCompositeStrategy());
    }

    /**
     * The five built-in strategies with {@code defaultStrategy} (a configured {@link WeightedCompositeStrategy}, for
     * example with the service-experience factor of ADR-044, Phase L4) in place of the plain default.
     */
    public AssignmentStrategyRegistry(AssignmentStrategy defaultStrategy) {
        if (defaultStrategy == null || !WeightedCompositeStrategy.STRATEGY_NAME.equalsIgnoreCase(defaultStrategy.getStrategyName())) {
            throw new IllegalArgumentException("the default strategy must be named " + WeightedCompositeStrategy.STRATEGY_NAME);
        }
        register(defaultStrategy);
        register(new SkillBasedStrategy());
        register(new LoadBasedStrategy());
        register(new ExperienceBasedStrategy());
        register(new RoundRobinStrategy());
        this.activeStrategyName = WeightedCompositeStrategy.STRATEGY_NAME;
    }

    // OOP (Open/Closed Principle): extension point, open for new strategies, closed for modification of the registry.
    public void register(AssignmentStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy cannot be null");
        strategies.put(strategy.getStrategyName().toUpperCase(), strategy);
    }

    public AssignmentStrategy getActiveStrategy() {
        AssignmentStrategy strategy = strategies.get(activeStrategyName.toUpperCase());
        if (strategy == null) {
            return strategies.get(WeightedCompositeStrategy.STRATEGY_NAME);
        }
        return strategy;
    }

    // OOP (Thread safety): synchronized so two admins switching strategy at once cannot corrupt the active choice.
    public synchronized void setActiveStrategy(String strategyName) {
        if (strategyName == null || !strategies.containsKey(strategyName.trim().toUpperCase())) {
            throw new ValidationException("strategyName", "unknown assignment strategy '"
                    + (strategyName == null ? "null" : strategyName.trim()) + "'. Valid strategies: "
                    + String.join(", ", new java.util.TreeSet<>(strategies.keySet())));
        }
        this.activeStrategyName = strategyName.trim().toUpperCase();
    }

    public String getActiveStrategyName() {
        return activeStrategyName;
    }

    public List<AssignmentStrategy> getAllStrategies() {
        return Collections.unmodifiableList(new ArrayList<>(strategies.values()));
    }

    public boolean hasStrategy(String strategyName) {
        return strategyName != null && strategies.containsKey(strategyName.trim().toUpperCase());
    }
}

