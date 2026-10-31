package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.infrastructure.persistence.entity.TicketSequenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataTicketSequenceRepository
        extends JpaRepository<TicketSequenceJpaEntity, TicketSequenceJpaEntity.Key> {

    /** Atomic database-side increment; holds the row lock until the surrounding transaction ends. */
    @Modifying
    @Query("UPDATE TicketSequenceJpaEntity s SET s.lastSequence = s.lastSequence + 1 "
            + "WHERE s.departmentCode = :code AND s.ticketYear = :year")
    int increment(@Param("code") String departmentCode, @Param("year") int year);

    @Query("SELECT s.lastSequence FROM TicketSequenceJpaEntity s "
            + "WHERE s.departmentCode = :code AND s.ticketYear = :year")
    long currentValue(@Param("code") String departmentCode, @Param("year") int year);

    /**
     * Plain INSERT of a new counter at 0 (nothing issued yet). Deliberately not {@code save}: merge would silently
     * overwrite a row another transaction just created, while this fails on the duplicate key.
     */
    @Modifying
    @Query(value = "INSERT INTO ticket_sequences (department_code, ticket_year, last_sequence) "
            + "VALUES (:code, :year, 0)", nativeQuery = true)
    int insertEmpty(@Param("code") String departmentCode, @Param("year") int year);
}
