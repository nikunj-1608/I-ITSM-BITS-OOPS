package com.iitsm;

import com.iitsm.infrastructure.config.AdjustableClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application entrypoint for the IT Service Management System (Swiftdesk).
 */
//we have majorly used the concepts of oops like encapsulation,inheritance,abstraction and polymorphism
@SpringBootApplication
@EnableScheduling
public class IitsmApplication {

    public static void main(String[] args) {
        SpringApplication.run(IitsmApplication.class, args);
    }

    /**
     * Injects an AdjustableClock instance allowing live simulation acceleration (x1, x60, x600)
     * and fast-forward jumps across the application. Primary since ADR-046: security code injects the separate
     * {@code wallClock} bean ({@link com.iitsm.infrastructure.config.SecurityTimeConfig}) by name, everything else
     * keeps getting this one.
     */
    @Bean
    @Primary
    public Clock clock() {
        return new AdjustableClock(Instant.now(), ZoneId.of("UTC"));
    }
}

