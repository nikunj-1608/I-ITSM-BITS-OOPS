package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Pattern: Factory. Generates the synthetic ticket stream of one replication from a seeded random generator and turns
 * it into in-memory incidents (ADR-044, Phase L3).
 *
 * <p>The stream is the "common random numbers" of the comparison: it is generated once per replication and replayed
 * through every strategy. Arrivals are a Poisson process (exponential gaps from {@link RandomGenerator#nextExponential});
 * priority and category come from fixed mixes (the categories restricted to those the pool can serve, so every ticket
 * can eventually be served); the text is one of three templates of the category; the work-time noise is drawn once.
 * The draws happen in a fixed order per ticket, so the same seed gives the same stream.
 */
public final class TicketStreamGenerator {

    /** Share of each priority in the stream; sums to 1. */
    public static final Map<Priority, Double> PRIORITY_MIX = priorityMix();
    /** Share of each category in the stream before it is restricted to the servable ones; sums to 1. */
    public static final Map<IncidentCategory, Double> CATEGORY_MIX = categoryMix();

    private static final int TEMPLATES_PER_CATEGORY = 3;
    private static final double MINUTES_PER_HOUR = 60.0;
    private static final double MILLIS_PER_MINUTE = 60_000.0;
    private static final String REPORTER_ID = "SIM-REPORTER";
    private static final String ID_FORMAT = "SIM-%06d";
    private static final double SIMULATED_COMPOSITE_SCORE = 0.5;

    private final WorkTimeModel model;

    public TicketStreamGenerator(WorkTimeModel model) {
        this.model = Objects.requireNonNull(model, "model cannot be null");
    }

    /** The tickets of one replication, in arrival order. */
    public List<SyntheticTicket> generate(RandomGenerator random, int count, double arrivalsPerHour, Set<IncidentCategory> servable) {
        Objects.requireNonNull(random, "random cannot be null");
        if (count < 1 || !(arrivalsPerHour > 0.0) || !Double.isFinite(arrivalsPerHour) || servable.isEmpty()) {
            throw new IllegalArgumentException("count and arrivalsPerHour must be positive and some category servable");
        }
        List<SyntheticTicket> stream = new ArrayList<>(count);
        double clockMinutes = 0.0;
        for (int i = 0; i < count; i++) {
            clockMinutes += random.nextExponential() * MINUTES_PER_HOUR / arrivalsPerHour;
            Priority priority = drawPriority(random);
            IncidentCategory category = drawCategory(random, servable);
            String[] text = TEMPLATES.get(category)[random.nextInt(TEMPLATES_PER_CATEGORY)];
            Duration arrival = Duration.ofMillis(Math.round(clockMinutes * MILLIS_PER_MINUTE));
            stream.add(new SyntheticTicket(i, arrival, priority, category, text[0], text[1], model.drawNoise(random)));
        }
        return Collections.unmodifiableList(stream);
    }

    /**
     * The same stream with an IT service on every ticket: one of {@code servicesPerCategory} services of its category
     * (named {@code SIM-<CATEGORY>-<k>}), drawn from {@code random}. Everything but the service id is unchanged, so a
     * stream with services is the base stream plus one extra draw per ticket from a generator of its own.
     */
    public List<SyntheticTicket> withServices(List<SyntheticTicket> stream, RandomGenerator random, int servicesPerCategory) {
        Objects.requireNonNull(random, "random cannot be null");
        if (servicesPerCategory < 1) {
            throw new IllegalArgumentException("servicesPerCategory must be at least 1");
        }
        List<SyntheticTicket> result = new ArrayList<>(stream.size());
        for (SyntheticTicket t : stream) {
            String serviceId = "SIM-" + t.category().name() + "-" + (random.nextInt(servicesPerCategory) + 1);
            result.add(new SyntheticTicket(t.index(), t.arrivalOffset(), t.priority(), t.category(), t.title(),
                    t.description(), t.noise(), serviceId));
        }
        return Collections.unmodifiableList(result);
    }

    /** Builds the in-memory incidents of a stream; no sequence, repository or clock is involved. */
    public List<PreparedTicket> prepare(List<SyntheticTicket> stream, Instant start) {
        List<PreparedTicket> prepared = new ArrayList<>(stream.size());
        for (SyntheticTicket ticket : stream) {
            Instant created = start.plus(ticket.arrivalOffset());
            Incident incident = new Incident(String.format(ID_FORMAT, ticket.index() + 1), ticket.title(),
                    ticket.description(), REPORTER_ID, created);
            PriorityDecision decision = new PriorityDecision(ticket.priority(), SIMULATED_COMPOSITE_SCORE,
                    List.of(), List.of(), created, null, null);
            incident.classify(ticket.category(), ticket.serviceId(), decision, SlaPolicy.defaultFor(ticket.priority()), created);
            prepared.add(new PreparedTicket(ticket, incident));
        }
        return Collections.unmodifiableList(prepared);
    }

    /** The stream assumptions in words. */
    public static List<String> assumptions() {
        return List.of("Tickets arrive as a Poisson process (exponential gaps) at the requested rate; priority mix P1 "
                + percent(PRIORITY_MIX.get(Priority.P1)) + ", P2 " + percent(PRIORITY_MIX.get(Priority.P2)) + ", P3 "
                + percent(PRIORITY_MIX.get(Priority.P3)) + ", P4 " + percent(PRIORITY_MIX.get(Priority.P4))
                + "; the category mix is fixed and restricted to the categories the pool can serve.");
    }

    private static String percent(double share) {
        return Math.round(share * 100) + " %";
    }

    private static Priority drawPriority(RandomGenerator random) {
        double draw = random.nextDouble();
        double cumulative = 0.0;
        Priority last = Priority.P4;
        for (Map.Entry<Priority, Double> entry : PRIORITY_MIX.entrySet()) {
            cumulative += entry.getValue();
            last = entry.getKey();
            if (draw < cumulative) {
                return entry.getKey();
            }
        }
        return last;
    }

    private static IncidentCategory drawCategory(RandomGenerator random, Set<IncidentCategory> servable) {
        double total = 0.0;
        for (IncidentCategory category : servable) {
            total += CATEGORY_MIX.get(category);
        }
        double draw = random.nextDouble() * total;
        double cumulative = 0.0;
        IncidentCategory last = null;
        for (IncidentCategory category : IncidentCategory.values()) {
            if (!servable.contains(category)) {
                continue;
            }
            cumulative += CATEGORY_MIX.get(category);
            last = category;
            if (draw < cumulative) {
                return category;
            }
        }
        return last;
    }

    private static Map<Priority, Double> priorityMix() {
        Map<Priority, Double> mix = new EnumMap<>(Priority.class);
        mix.put(Priority.P1, 0.05);
        mix.put(Priority.P2, 0.15);
        mix.put(Priority.P3, 0.50);
        mix.put(Priority.P4, 0.30);
        return Collections.unmodifiableMap(mix);
    }

    private static Map<IncidentCategory, Double> categoryMix() {
        Map<IncidentCategory, Double> mix = new EnumMap<>(IncidentCategory.class);
        mix.put(IncidentCategory.HARDWARE, 0.15);
        mix.put(IncidentCategory.SOFTWARE, 0.15);
        mix.put(IncidentCategory.NETWORK, 0.15);
        mix.put(IncidentCategory.DATABASE, 0.08);
        mix.put(IncidentCategory.CLOUD, 0.08);
        mix.put(IncidentCategory.SECURITY, 0.08);
        mix.put(IncidentCategory.AUTHENTICATION, 0.10);
        mix.put(IncidentCategory.APPLICATION, 0.16);
        mix.put(IncidentCategory.STORAGE, 0.05);
        return Collections.unmodifiableMap(mix);
    }

    /** Title and description templates; the text contains the technical words the strategies' skill matching reads. */
    private static final Map<IncidentCategory, String[][]> TEMPLATES = templates();

    private static Map<IncidentCategory, String[][]> templates() {
        Map<IncidentCategory, String[][]> t = new EnumMap<>(IncidentCategory.class);
        t.put(IncidentCategory.NETWORK, new String[][] {
            {"VPN gateway drops remote sessions", "Remote staff lose the VPN tunnel every few minutes with packet loss on the BGP router."},
            {"Branch office Cisco router link down", "The branch office Cisco router shows a red WAN link and DNS lookups fail for everyone."},
            {"Wi-Fi access points keep disconnecting", "Wireless clients disconnect from the office access points and DHCP leases are not renewed."}});
        t.put(IncidentCategory.DATABASE, new String[][] {
            {"PostgreSQL queries time out on the ledger", "A slow query on the ledger table blocks the nightly PostgreSQL job and the report fails."},
            {"Database replication lag keeps growing", "The standby replica lags behind the primary database by several minutes during peak hours."},
            {"SQL deadlocks during the payment batch", "Deadlocks appear in the SQL server log while the payment batch updates the account tables."}});
        t.put(IncidentCategory.CLOUD, new String[][] {
            {"Terraform apply fails creating AWS network", "The Terraform apply for the staging VPC fails with an AWS security group rule limit error."},
            {"Kubernetes pods restart in a loop", "Pods of the checkout service crash and restart repeatedly on the Kubernetes cluster."},
            {"Cloud storage bucket access denied", "Services get access denied from the cloud bucket after the IAM role policy change."}});
        t.put(IncidentCategory.SECURITY, new String[][] {
            {"Phishing e-mail reported by finance", "Several finance staff received a phishing e-mail asking them to confirm their password today."},
            {"Endpoint antivirus detected malware", "The endpoint antivirus quarantined malware on a laptop and the machine needs a forensic review."},
            {"Firewall rule blocks the partner API", "A firewall rule change blocks the partner API traffic and the security team must review it."}});
        t.put(IncidentCategory.AUTHENTICATION, new String[][] {
            {"Cannot sign in after password reset", "After the password reset the login fails with invalid credentials on the single sign-on page."},
            {"MFA push notification never arrives", "The multi-factor push notification never reaches the phone so the login times out."},
            {"Account locked after failed logins", "The user account is locked after failed login attempts and needs an administrator unlock."}});
        t.put(IncidentCategory.APPLICATION, new String[][] {
            {"Customer CRM shows an empty balance tab", "In the customer CRM the account balance history tab stays empty for every customer record."},
            {"Checkout service returns server errors", "The checkout service returns HTTP 500 errors for card payments since the last deployment of Java code."},
            {"Report export in the portal fails", "The monthly report export in the internal portal fails with a timeout error message."}});
        t.put(IncidentCategory.SOFTWARE, new String[][] {
            {"Spreadsheet macro crashes the office suite", "Opening the quarterly spreadsheet crashes the office suite after the macro starts running."},
            {"Application installer fails with an error", "The installer for the design application stops with an error code during the install step."},
            {"Browser plugin blocks internal pages", "A browser extension blocks several internal pages and the login form never loads."}});
        t.put(IncidentCategory.HARDWARE, new String[][] {
            {"Laptop screen flickers and shows lines", "The laptop display flickers and shows vertical lines when the hinge is moved."},
            {"Printer on the second floor jams", "The shared printer on the second floor jams on every page and shows a paper tray error."},
            {"Docking station does not power monitors", "The docking station does not power the external monitors and the keyboard is dead."}});
        t.put(IncidentCategory.STORAGE, new String[][] {
            {"File server volume almost full", "The shared file server volume is almost full and users cannot save new documents."},
            {"Backup job fails on the storage array", "The nightly backup job fails on the storage array with a disk read error."},
            {"SAN LUN missing after maintenance", "A SAN LUN is missing on the database hosts after the storage maintenance window."}});
        return Collections.unmodifiableMap(t);
    }
}
