package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.model.Notification;
import com.iitsm.domain.repository.NotificationRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Remaining-defects plan, Phase E (ADR-032, stress-audit item M3): {@code POST /api/notifications/{id}/read} marked any
 * notification as read for any signed-in user, who only needed its id. The owner is checked now; for anyone else the
 * notification does not exist (HTTP 404, nothing changed), like a notification that does not exist at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class NotificationOwnershipRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String OWNER = "employee@nimbusfinserve.com";
    private static final String OWNER_ID = "EMP-DEMO-01";
    private static final String OTHER_EMPLOYEE = "ceo@nimbusfinserve.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private String token(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String plantNotification() {
        String id = "NOTIF-" + UUID.randomUUID();
        notificationRepository.save(new Notification(id, OWNER_ID, "IN_APP", "Ticket update", "Your ticket moved on",
                false, Instant.now()));
        return id;
    }

    private boolean delivered(String id) {
        return notificationRepository.findById(id).orElseThrow().delivered();
    }

    @Test
    @DisplayName("M3: another employee cannot mark someone else's notification as read; it answers 404 and stays unread")
    void anotherEmployeeGetsNotFound() throws Exception {
        String id = plantNotification();

        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", "Bearer " + token(OTHER_EMPLOYEE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));

        assertThat(delivered(id)).isFalse();
    }

    @Test
    @DisplayName("M3: a technician and an administrator are not owners either")
    void staffAreNotOwnersEither() throws Exception {
        String id = plantNotification();

        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", "Bearer " + token("tech.network@nimbusfinserve.com")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", "Bearer " + token("admin@nimbusfinserve.com")))
                .andExpect(status().isNotFound());

        assertThat(delivered(id)).isFalse();
    }

    @Test
    @DisplayName("M3: the owner marks the notification as read")
    void ownerMarksItRead() throws Exception {
        String id = plantNotification();

        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", "Bearer " + token(OWNER)))
                .andExpect(status().isNoContent());

        assertThat(delivered(id)).isTrue();
    }

    @Test
    @DisplayName("M3: an id that does not exist answers 404, the same as someone else's (no way to tell them apart)")
    void unknownIdAnswersNotFound() throws Exception {
        mockMvc.perform(post("/api/notifications/NOTIF-" + UUID.randomUUID() + "/read")
                        .header("Authorization", "Bearer " + token(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Marking a notification as read keeps its other fields")
    void readKeepsTheOtherFields() throws Exception {
        String id = plantNotification();

        mockMvc.perform(post("/api/notifications/" + id + "/read").header("Authorization", "Bearer " + token(OWNER)))
                .andExpect(status().isNoContent());

        Notification after = notificationRepository.findById(id).orElseThrow();
        assertThat(after.recipientId()).isEqualTo(OWNER_ID);
        assertThat(after.subject()).isEqualTo("Ticket update");
        assertThat(after.message()).isEqualTo("Your ticket moved on");
    }
}
