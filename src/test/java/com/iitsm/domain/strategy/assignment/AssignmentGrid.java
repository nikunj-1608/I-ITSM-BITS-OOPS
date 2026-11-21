package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fixed grid of technicians, tickets and candidate lists for the assignment strategy tests (ADR-044).
 *
 * <p>The grid covers every priority and an unclassified ticket (no priority, no category), skills matched through the
 * category, through the full skill name in the text and through single tokens, exact ties in both orders, an experience
 * tie broken by load, a one-element list, and experience beyond both scaling limits (12 and 15 years). Every call
 * builds new objects, so tests cannot leak state into each other.
 */
final class AssignmentGrid {

    static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");

    private AssignmentGrid() {
    }

    /** Technicians by id, in a fixed order. */
    static Map<String, Technician> technicians() {
        Map<String, Technician> techs = new LinkedHashMap<>();
        techs.put("T-NET-A", tech("T-NET-A", 6, 6, 0,
                new Skill("Cisco Routing", IncidentCategory.NETWORK), new Skill("BGP", IncidentCategory.NETWORK)));
        techs.put("T-NET-B", tech("T-NET-B", 6, 6, 0,
                new Skill("Cisco Routing", IncidentCategory.NETWORK), new Skill("BGP", IncidentCategory.NETWORK)));
        techs.put("T-APP", tech("T-APP", 10, 8, 1,
                new Skill("Java", IncidentCategory.APPLICATION), new Skill("Spring Boot", IncidentCategory.APPLICATION)));
        techs.put("T-JUN", tech("T-JUN", 2, 5, 4, new Skill("Java", IncidentCategory.APPLICATION)));
        techs.put("T-VET", tech("T-VET", 15, 4, 2,
                new Skill("Firewall Rules", IncidentCategory.SECURITY), new Skill("VPN Gateway", IncidentCategory.NETWORK)));
        techs.put("T-VET2", tech("T-VET2", 15, 4, 1, new Skill("Storage Arrays", IncidentCategory.STORAGE)));
        techs.put("T-OLD", tech("T-OLD", 20, 10, 5, new Skill("Database Tuning", IncidentCategory.DATABASE)));
        techs.put("T-NONE", tech("T-NONE", 0, 3, 0));
        return techs;
    }

    /** Candidate lists by name; built from a fresh set of technicians. */
    static Map<String, List<Technician>> candidateLists() {
        Map<String, Technician> t = technicians();
        List<Technician> all = new ArrayList<>(t.values());
        List<Technician> reversed = new ArrayList<>(all);
        java.util.Collections.reverse(reversed);
        Map<String, List<Technician>> lists = new LinkedHashMap<>();
        lists.put("ALL", List.copyOf(all));
        lists.put("ALL_REVERSED", List.copyOf(reversed));
        lists.put("ONE", List.of(t.get("T-JUN")));
        lists.put("TIE", List.of(t.get("T-NET-A"), t.get("T-NET-B")));
        lists.put("TIE_REVERSED", List.of(t.get("T-NET-B"), t.get("T-NET-A")));
        lists.put("EXPERIENCE_TIE", List.of(t.get("T-VET"), t.get("T-VET2")));
        lists.put("EXPERIENCE_TIE_REVERSED", List.of(t.get("T-VET2"), t.get("T-VET")));
        lists.put("MIXED", List.of(t.get("T-JUN"), t.get("T-APP"), t.get("T-NONE"), t.get("T-OLD")));
        return lists;
    }

    /** Tickets by name: every priority for two categories, a token-only match, and two unclassified tickets. */
    static Map<String, Incident> incidents() {
        Map<String, Incident> incidents = new LinkedHashMap<>();
        for (Priority priority : Priority.values()) {
            incidents.put("NET_" + priority, classified("BGP route flap causing network drops",
                    "Packet loss across Cisco router", IncidentCategory.NETWORK, priority));
            incidents.put("APP_" + priority, classified("Spring Boot service crash",
                    "Java exception in the checkout flow", IncidentCategory.APPLICATION, priority));
        }
        incidents.put("VPN_P2", classified("Gateway timeout for vpn users",
                "Remote staff cannot reach the vpn", IncidentCategory.NETWORK, Priority.P2));
        incidents.put("RAW_VAGUE", new Incident("RAW-1", "Something is wrong", "please help, nothing works today", "EMP-1", NOW));
        incidents.put("RAW_JAVA", new Incident("RAW-2", "Java Spring Boot error", "stack trace shown on every start", "EMP-1", NOW));
        return incidents;
    }

    private static Incident classified(String title, String description, IncidentCategory category, Priority priority) {
        Incident incident = new Incident("INC-" + category + "-" + priority, title, description, "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(priority, 0.5, List.of(), List.of(), NOW, null, null);
        incident.classify(category, "SVC-TEST", decision, SlaPolicy.defaultFor(priority), NOW);
        return incident;
    }

    private static Technician tech(String id, int experience, int capacity, int active, Skill... skills) {
        Technician technician = new Technician(id, "Name " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(skills), experience, capacity, "TEAM-" + id);
        for (int i = 0; i < active; i++) {
            technician.assignIncident(id + "-ACTIVE-" + i);
        }
        return technician;
    }
}
