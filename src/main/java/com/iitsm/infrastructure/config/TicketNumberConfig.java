package com.iitsm.infrastructure.config;

import com.iitsm.domain.policy.DepartmentCodeResolver;
import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration wiring the ticket numbering strategy. Replace the bean (or register more
 * departments on it) to change how departments map to ticket-number codes.
 */
@Configuration
public class TicketNumberConfig {

    @Bean
    public DepartmentCodeResolver departmentCodeResolver() {
        return new RegistryDepartmentCodeResolver();
    }
}
