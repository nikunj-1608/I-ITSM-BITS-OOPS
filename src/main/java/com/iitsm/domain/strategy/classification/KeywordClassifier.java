package com.iitsm.domain.strategy.classification;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.strategy.nlp.TextNormalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pattern: Strategy.
 * Rule-based classifier matching high-precision domain keywords and technical lexicons.
 *
 * <p>Evidence rules (ADR-034): every distinct term counts once, however often it is repeated; a tie between categories is
 * reported as split evidence; and the "strong" confidence needs a clear lead over the next category. A strong security
 * indicator is reported too, so the composite classifier can send a ticket that mentions one to a person. The lexicon holds no word that is
 * ordinary English outside IT (see {@link #GENERIC_WORDS}); duplicate detection keeps those words in its own frozen
 * vocabulary. Two ordinary words that together are IT evidence ("account" and "locked") count as one term through
 * {@link #PAIR_TERMS} (ADR-052).
 */
public class KeywordClassifier implements IncidentClassifier {

    private static final String CLASSIFIER_NAME = "KEYWORD_CLASSIFIER";
    private static final double SINGLE_TITLE_MATCH_CONFIDENCE = 0.55;
    private static final double WEAK_SINGLE_MATCH_CONFIDENCE = 0.50;
    /** Split evidence: a second model must break the tie, so the keywords alone stay below the auto-assignment gate. */
    private static final double AMBIGUOUS_CONFIDENCE = 0.55;
    private static final double NO_MATCH_CONFIDENCE = 0.20;
    private static final double MODERATE_CONFIDENCE = 0.75;
    private static final double MIXED_STRONG_CONFIDENCE = 0.75;
    private static final double STRONG_CONFIDENCE = 0.90;
    private static final int STRONG_MATCHES = 3;
    private static final int MODERATE_MATCHES = 2;
    /** How many more distinct terms the best category needs over the next one to be treated as a clear winner. */
    static final int CLEAR_LEAD = 2;

    /**
     * Words that are ordinary English outside IT and so are no evidence for a category ("power switch", "locked drawer",
     * "service door", "order", "port", "pin", "connection"). They were in the lexicon until ADR-034 and sent a coffee
     * machine to the Network team and a desk drawer to Authentication; the lexicon must not contain them.
     */
    public static final Set<String> GENERIC_WORDS =
            Set.of("locked", "pin", "power", "port", "service", "order", "connection", "switch");

    /**
     * Terms that, on their own, suggest a security incident. A ticket that mentions one while another category wins is
     * not routed automatically: a human decides whether it is a security incident (phishing with an Excel macro).
     */
    static final Set<String> SECURITY_ALERT_TERMS = Set.of(
            "phishing", "malware", "ransomware", "breach", "unauthorized", "hacker", "trojan", "suspicious",
            "compromised", "vulnerability", "virus");

    /**
     * Two ordinary words that together are evidence for a category (ADR-052), although neither is on its own: "account"
     * and "locked" anywhere in the ticket mean a locked sign-in account, while "locked" alone (a desk drawer) or "account"
     * alone (a bank account) means nothing. A pair counts as one term, labelled "first+second".
     */
    public record PairTerm(IncidentCategory category, String first, String second) {

        public PairTerm {
            Objects.requireNonNull(category, "category cannot be null");
            Objects.requireNonNull(first, "first cannot be null");
            Objects.requireNonNull(second, "second cannot be null");
        }

        /** The label the pair is reported with among the matched terms. */
        public String label() {
            return first + PAIR_SEPARATOR + second;
        }

        boolean presentIn(Set<String> tokens) {
            return hasForm(tokens, first) && hasForm(tokens, second);
        }
    }

    private static final String PAIR_SEPARATOR = "+";

    /** The declared pairs; a new pair is one more entry here. */
    public static final List<PairTerm> PAIR_TERMS = List.of(
            new PairTerm(IncidentCategory.AUTHENTICATION, "account", "locked"),
            new PairTerm(IncidentCategory.AUTHENTICATION, "account", "disabled"));

    private final Map<IncidentCategory, Set<String>> categoryLexicon = new EnumMap<>(IncidentCategory.class);
    /** Every lexicon word and its stem, per category; what a normalised token is compared with. */
    private final Map<IncidentCategory, Set<String>> matchForms = new EnumMap<>(IncidentCategory.class);
    private final Set<String> securityAlertForms = new HashSet<>();

    public KeywordClassifier() {
        initLexicon();
        categoryLexicon.forEach((category, words) -> {
            Set<String> forms = new HashSet<>();
            words.forEach(word -> {
                forms.add(word);
                forms.add(TextNormalizer.stem(word));
            });
            matchForms.put(category, forms);
        });
        SECURITY_ALERT_TERMS.forEach(word -> {
            securityAlertForms.add(word);
            securityAlertForms.add(TextNormalizer.stem(word));
        });
    }

    private void initLexicon() {
        categoryLexicon.put(IncidentCategory.HARDWARE, Set.of(
                "screen", "monitor", "keyboard", "mouse", "fan", "battery", "overheating", "overheat",
                "trackpad", "display", "dock", "docking", "laptop", "ram", "gpu", "cpu",
                "chassis", "hardware", "cable", "hdmi", "displayport", "headset", "audio",
                // ADR-052: everyday words employees use for office devices
                "printer", "print", "toner", "jam", "scanner", "webcam", "camera", "charger", "usb"
        ));

        categoryLexicon.put(IncidentCategory.SOFTWARE, Set.of(
                "excel", "outlook", "teams", "visual", "studio", "ide", "crash", "freeze", "freezing",
                "dll", "patch", "chrome", "acrobat", "bug", "update", "macro", "slack", "software",
                "app", "application", "browser", "driver", "install", "compilation",
                "git", "gitlab", "github", "repository", "ssh", "intellij", "vscode", "maven", "gradle",
                // ADR-052: e-mail clients and meeting tools
                "email", "mail", "inbox", "outbox", "zoom", "webex"
        ));

        categoryLexicon.put(IncidentCategory.NETWORK, Set.of(
                "vpn", "wifi", "wireless", "dns", "latency", "router", "packet", "subnet",
                "ip", "bgp", "stp", "lan", "wan", "anyconnect", "gateway", "flapping", "ethernet",
                "bandwidth", "firewall", "ssid", "network",
                // ADR-052: how employees describe a lost connection
                "internet", "unreachable", "connectivity", "proxy"
        ));

        categoryLexicon.put(IncidentCategory.DATABASE, Set.of(
                "postgresql", "postgres", "oracle", "mysql", "redis", "database", "query", "sql",
                "deadlock", "tablespace", "pool", "hikaricp", "hikari", "replication", "mongo",
                "mongodb", "rdbms", "table", "index", "db", "transaction"
        ));

        categoryLexicon.put(IncidentCategory.CLOUD, Set.of(
                "aws", "ec2", "s3", "azure", "gcp", "kubernetes", "k8s", "pod", "terraform",
                "lambda", "docker", "container", "cloudfront", "iam", "oomkilled", "cluster",
                "cloud", "microservice", "ingress", "pubsub",
                // ADR-052
                "vm"
        ));

        categoryLexicon.put(IncidentCategory.SECURITY, Set.of(
                "phishing", "malware", "ransomware", "breach", "unauthorized", "hacker", "soc",
                "edr", "crowdstrike", "trojan", "suspicious", "sudo", "scan", "dlp", "vulnerability",
                "leak", "security", "threat", "waf", "injection", "compromised",
                "attack", "brute", "exploit",
                // ADR-052: the words of an antivirus warning
                "virus", "antivirus"
        ));

        categoryLexicon.put(IncidentCategory.AUTHENTICATION, Set.of(
                "okta", "mfa", "2fa", "sso", "saml", "kerberos", "password", "login", "credentials",
                "active", "directory", "ldap", "lockout", "token", "yubikey", "webauthn",
                "auth", "authenticate", "fido2",
                // ADR-052
                "unlock"
        ));

        categoryLexicon.put(IncidentCategory.APPLICATION, Set.of(
                "portal", "crm", "erp", "benefits", "invoice", "hrms", "payroll", "banking",
                "transfer", "billing", "payment", "checkout", "wiki", "intranet", "frontend",
                "concur", "salesforce", "workday", "website"
        ));

        categoryLexicon.put(IncidentCategory.STORAGE, Set.of(
                "san", "nas", "lun", "backup", "raid", "nfs", "iscsi", "veeam", "commvault",
                "disk", "tape", "volume", "datastore", "storage", "drive", "pool", "filesystem"
        ));
    }

    /**
     * Distinct domain terms found per category. A term counts once however often it is repeated ("vpn vpn vpn" is one
     * term); {@code countsByCategory} lists every category with at least one term.
     */
    public record Evidence(
            IncidentCategory category,
            List<String> terms,
            Map<IncidentCategory, Integer> countsByCategory,
            boolean securityAlert) {

        public int count() {
            return terms.size();
        }

        /** The largest count among the other categories; 0 when the best category stands alone. */
        public int runnerUpCount() {
            return countsByCategory.entrySet().stream()
                    .filter(e -> e.getKey() != category)
                    .mapToInt(Map.Entry::getValue).max().orElse(0);
        }

        /** The other categories with exactly as many terms as the best one. */
        public Set<IncidentCategory> tiedWith() {
            if (count() == 0) {
                return Set.of();
            }
            Set<IncidentCategory> tied = new TreeSet<>();
            countsByCategory.forEach((other, n) -> {
                if (other != category && n == count()) {
                    tied.add(other);
                }
            });
            return Set.copyOf(tied);
        }

        public boolean tie() {
            return !tiedWith().isEmpty();
        }

        /** The best category leads the next one by at least {@value KeywordClassifier#CLEAR_LEAD} terms, or stands alone. */
        public boolean hasClearLead() {
            return runnerUpCount() == 0 || count() - runnerUpCount() >= CLEAR_LEAD;
        }
    }

    /** Collects the distinct lexicon terms of every category and picks the category with the most. */
    public Evidence evidence(String title, String description) {
        String fullText = (title != null ? title : "") + " " + (description != null ? description : "");
        Set<String> tokens = new LinkedHashSet<>(TextNormalizer.tokenize(fullText));

        IncidentCategory best = IncidentCategory.APPLICATION;
        List<String> bestTerms = List.of();
        Map<IncidentCategory, Integer> counts = new EnumMap<>(IncidentCategory.class);
        for (Map.Entry<IncidentCategory, Set<String>> entry : matchForms.entrySet()) {
            List<String> found = new ArrayList<>(tokens.stream().filter(entry.getValue()::contains).toList());
            PAIR_TERMS.stream()
                    .filter(pair -> pair.category() == entry.getKey() && pair.presentIn(tokens))
                    .forEach(pair -> found.add(pair.label()));
            if (!found.isEmpty()) {
                counts.put(entry.getKey(), found.size());
            }
            if (found.size() > bestTerms.size()) {
                best = entry.getKey();
                bestTerms = found;
            }
        }
        boolean alert = tokens.stream().anyMatch(securityAlertForms::contains);
        return new Evidence(best, bestTerms, counts, alert);
    }

    @Override
    public ClassificationResult classify(String title, String description) {
        Evidence evidence = evidence(title, description);
        int matches = evidence.count();
        List<String> rationale = new ArrayList<>();
        double confidence;
        if (matches == 0) {
            confidence = NO_MATCH_CONFIDENCE;
            rationale.add("No explicit domain keywords matched; defaulted to APPLICATION");
        } else if (evidence.tie()) {
            confidence = AMBIGUOUS_CONFIDENCE;
            rationale.add("Keyword evidence is split between categories (" + matches + " term(s) each); no clear winner");
        } else if (matches >= STRONG_MATCHES) {
            confidence = evidence.hasClearLead() ? STRONG_CONFIDENCE : MIXED_STRONG_CONFIDENCE;
            rationale.add((evidence.hasClearLead() ? "Strong keyword match (" : "Keyword match with competing evidence (")
                    + matches + " distinct terms): " + evidence.terms());
        } else if (matches == MODERATE_MATCHES) {
            confidence = MODERATE_CONFIDENCE;
            rationale.add("Moderate keyword match (" + matches + " distinct terms): " + evidence.terms());
        } else if (appearsInTitle(title, evidence.terms().getFirst())) {
            confidence = SINGLE_TITLE_MATCH_CONFIDENCE;
            rationale.add("Single keyword match in title: " + evidence.terms());
        } else {
            confidence = WEAK_SINGLE_MATCH_CONFIDENCE;
            rationale.add("Weak single keyword match: " + evidence.terms());
        }
        if (evidence.securityAlert()) {
            rationale.add("Security indicator present");
        }
        return ClassificationResult.of(evidence.category(), confidence, CLASSIFIER_NAME, rationale,
                evidence.countsByCategory(), evidence.securityAlert());
    }

    /** The categories with at least one lexicon or pair term in the title alone (ADR-052: the title names the problem). */
    public Set<IncidentCategory> titleCategories(String title) {
        return Set.copyOf(evidence(title, "").countsByCategory().keySet());
    }

    /** The lexicon words of a category, for tests and documentation. */
    public Set<String> lexiconWords(IncidentCategory category) {
        return Set.copyOf(categoryLexicon.get(category));
    }

    /** A term, or every word of a pair term, is in the title. */
    private static boolean appearsInTitle(String title, String term) {
        if (title == null) {
            return false;
        }
        Set<String> titleTokens = new HashSet<>(TextNormalizer.tokenize(title));
        return PAIR_TERMS.stream().filter(pair -> pair.label().equals(term)).findFirst()
                .map(pair -> pair.presentIn(titleTokens))
                .orElseGet(() -> titleTokens.contains(term));
    }

    private static boolean hasForm(Set<String> tokens, String word) {
        return tokens.contains(word) || tokens.contains(TextNormalizer.stem(word));
    }

    @Override
    public String getName() {
        return CLASSIFIER_NAME;
    }
}
