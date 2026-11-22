package com.iitsm.domain.strategy.nlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for pure-Java TfIdfVectorizer and cosine similarity.
 */
class TfIdfVectorizerTest {

    @Test
    @DisplayName("Trains IDF weights and transforms text into normalized feature vectors")
    void testTrainAndTransform() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        List<String> corpus = List.of(
                "Oracle database deadlock on transaction table",
                "PostgreSQL connection pool exhausted by database clients",
                "Laptop screen flickering and hardware display failure",
                "Cisco network switch port flapping and packet loss"
        );

        vectorizer.train(corpus);
        assertThat(vectorizer.getVocabularySize()).isGreaterThan(10);

        Map<String, Double> vec = vectorizer.transform("PostgreSQL database deadlock");
        assertThat(vec).isNotEmpty();
        assertThat(vec).containsKey("databas");

        // L2 unit length norm
        double sumSquares = vec.values().stream().mapToDouble(v -> v * v).sum();
        assertThat(sumSquares).isCloseTo(1.0, within(0.01));
    }

    @Test
    @DisplayName("Identical texts have cosine similarity of 1.0; unrelated have near 0.0")
    void testCosineSimilarity() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of(
                "AnyConnect VPN gateway timeout and dropped packets",
                "Laptop battery overheating and swelling chassis",
                "Employee benefits payroll portal HTTP 500 error"
        ));

        String textA = "VPN connection failed with gateway timeout";
        String textB = "VPN client cannot connect to gateway timeout error";
        String textC = "Laptop battery is overheating and melting plastic";

        Map<String, Double> vecA = vectorizer.transform(textA);
        Map<String, Double> vecB = vectorizer.transform(textB);
        Map<String, Double> vecC = vectorizer.transform(textC);

        double simAB = vectorizer.cosineSimilarity(vecA, vecB);
        double simAC = vectorizer.cosineSimilarity(vecA, vecC);

        // ADR-016: words absent from this 3-document toy corpus now carry full weight, so a paraphrase scores lower
        // than before; what matters is that it stays far above the unrelated pair.
        assertThat(simAB).isGreaterThan(0.50);
        assertThat(simAB).isGreaterThan(simAC + 0.30);
        assertThat(simAC).isLessThan(0.20);
    }

    @Test
    @DisplayName("ADR-016: a word never seen in training outweighs a word seen in most training documents")
    void unseenWordsOutweighCommonCorpusWords() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of(
                "vpn gateway timeout", "vpn gateway dropped", "vpn gateway slow", "vpn gateway error"));

        Map<String, Double> vec = vectorizer.transform("vpn zq7hostname");

        double unseen = vec.entrySet().stream().filter(e -> e.getKey().startsWith("zq7"))
                .mapToDouble(Map.Entry::getValue).findFirst().orElseThrow();
        assertThat(unseen).isGreaterThan(vec.get("vpn"));
    }

    @Test
    @DisplayName("ADR-016: two tickets that share only template words but differ in specific details are not similar")
    void sharedTemplateWordsDoNotMakeDifferentTicketsSimilar() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of("laptop keyboard monitor battery", "laptop screen dock", "keyboard mouse headset"));

        double sim = vectorizer.cosineSimilarity(
                vectorizer.transform("laptop keyboard qwfpgj zxcvbn mnbvcx lkjhgf poiuyt"),
                vectorizer.transform("laptop keyboard asdfgh trewqa yuiopl hgfdsa vcxzmn"));

        assertThat(sim).isLessThan(0.30);
    }

    @Test
    @DisplayName("ADR-016: repeating a word inside one text does not change its similarity to a once-mentioned copy much")
    void repeatedWordCountsOnceWithLogBonus() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of("vpn gateway timeout", "laptop battery swelling", "payroll portal error"));

        double once = vectorizer.cosineSimilarity(
                vectorizer.transform("vpn gateway timeout"), vectorizer.transform("vpn gateway timeout"));
        double repeated = vectorizer.cosineSimilarity(
                vectorizer.transform("vpn gateway timeout"), vectorizer.transform("vpn vpn vpn vpn gateway timeout"));

        assertThat(once).isCloseTo(1.0, within(1e-9));
        assertThat(repeated).isGreaterThan(0.90);
    }

    @Test
    @DisplayName("An untrained vectorizer still produces unit-length vectors")
    void untrainedVectorizerStillWorks() {
        Map<String, Double> vec = new TfIdfVectorizer().transform("vpn gateway timeout");

        double sumSquares = vec.values().stream().mapToDouble(v -> v * v).sum();
        assertThat(sumSquares).isCloseTo(1.0, within(0.01));
    }
}
