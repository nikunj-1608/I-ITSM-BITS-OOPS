package com.iitsm.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Persisted row of the explainable priority breakdown (one per priority factor per incident).
 * Maps the {@code factor_contributions} table so the breakdown survives a save/load round trip.
 */
@Entity
@Table(name = "factor_contributions")
public class FactorContributionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private IncidentJpaEntity incident;

    @Column(name = "factor_name", nullable = false, length = 64)
    private String factorName;

    @Column(name = "raw_score", nullable = false)
    private double rawScore;

    @Column(name = "weight", nullable = false)
    private double weight;

    @Column(name = "weighted_score", nullable = false)
    private double weightedScore;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    public FactorContributionJpaEntity() {}

    public Long getId() { return id; }

    public IncidentJpaEntity getIncident() { return incident; }
    public void setIncident(IncidentJpaEntity incident) { this.incident = incident; }

    public String getFactorName() { return factorName; }
    public void setFactorName(String factorName) { this.factorName = factorName; }

    public double getRawScore() { return rawScore; }
    public void setRawScore(double rawScore) { this.rawScore = rawScore; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public double getWeightedScore() { return weightedScore; }
    public void setWeightedScore(double weightedScore) { this.weightedScore = weightedScore; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
