package com.iitsm.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Persisted counter of the last ticket number issued for one department code in one year.
 * Maps the {@code ticket_sequences} table.
 */
@Entity
@Table(name = "ticket_sequences")
@IdClass(TicketSequenceJpaEntity.Key.class)
public class TicketSequenceJpaEntity {

    @Id
    @Column(name = "department_code", nullable = false, length = 8)
    private String departmentCode;

    @Id
    @Column(name = "ticket_year", nullable = false)
    private int ticketYear;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    protected TicketSequenceJpaEntity() {
        // required by JPA
    }

    public String getDepartmentCode() { return departmentCode; }
    public int getTicketYear() { return ticketYear; }
    public long getLastSequence() { return lastSequence; }

    /** Composite primary key (department code, year). */
    public static class Key implements Serializable {

        private String departmentCode;
        private int ticketYear;

        public Key() {
            // required by JPA
        }

        public Key(String departmentCode, int ticketYear) {
            this.departmentCode = departmentCode;
            this.ticketYear = ticketYear;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return ticketYear == key.ticketYear && Objects.equals(departmentCode, key.departmentCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(departmentCode, ticketYear);
        }
    }
}
