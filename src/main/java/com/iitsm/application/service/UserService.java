package com.iitsm.application.service;

import com.iitsm.application.policy.RegistrationPolicy;
import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.DuplicateIncidentException;
import com.iitsm.domain.exception.InvalidCredentialsException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.TeamLead;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.model.UserRole;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service managing user credentials, registration, and role lifecycle.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final TechnicianRepository technicianRepository;
    private final UserFactory userFactory;
    private final PasswordEncoder passwordEncoder;
    private final TokenPort tokenProvider;
    private final LoginAttemptService loginAttemptService;
    private final RegistrationPolicy registrationPolicy;
    private final Clock clock;
    private final int maxFailedLogins;

    public UserService(
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            UserFactory userFactory,
            PasswordEncoder passwordEncoder,
            TokenPort tokenProvider,
            LoginAttemptService loginAttemptService,
            RegistrationPolicy registrationPolicy,
            Clock clock,
            @Value("${iitsm.security.max-failed-logins:5}") int maxFailedLogins) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.userFactory = Objects.requireNonNull(userFactory, "userFactory cannot be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder cannot be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider cannot be null");
        this.loginAttemptService = Objects.requireNonNull(loginAttemptService, "loginAttemptService cannot be null");
        this.registrationPolicy = Objects.requireNonNull(registrationPolicy, "registrationPolicy cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.maxFailedLogins = maxFailedLogins;
    }

    public record AuthResult(String token, User user) {}

    /**
     * Checks the credentials. Deliberately carries no {@code @Transactional} at all, and this class has none at class
     * level: the lookup and the counter updates of {@link LoginAttemptService} each run in their own short transaction,
     * so no login holds a connection while it checks the password or waits for another connection (ADR-033), and the
     * failure counter is committed before the exception is thrown. Even {@code SUPPORTS} is not enough: Spring then opens
     * an "empty" transaction scope whose persistence context keeps the connection of the first query until the method
     * ends (measured: a pool of 3 starved with 12 simultaneous logins).
     */
    public AuthResult authenticate(String emailStr, String rawPassword) {
        User user = verifyCredentials(emailStr, rawPassword);
        return new AuthResult(tokenProvider.generateToken(user), user);
    }

    /**
     * Checks the credentials and returns the user, with the failure counting and the lock rules of {@link #authenticate}
     * (the same generic "Invalid credentials" for every failure). Like {@code authenticate} it has no transaction at
     * all (ADR-033); {@code AuthSessionService.login} builds the session from it (ADR-047).
     */
    public User verifyCredentials(String emailStr, String rawPassword) {
        if (emailStr == null || rawPassword == null) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        Email email = new Email(emailStr.trim());
        User user = userRepository.findByEmail(email)
                .orElse(null);

        if (user == null) {
            log.warn("Authentication failed: user not found for '{}'", emailStr);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!user.isActive()) {
            log.warn("Authentication failed: user '{}' is deactivated", emailStr);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (user.isCurrentlyLocked(clock.instant())) {
            log.warn("Authentication failed: user '{}' is locked", emailStr);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("Authentication failed: invalid password for '{}'", emailStr);
            loginAttemptService.recordFailedAttempt(emailStr);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        // Successful authentication
        // ADR-048: with two-step verification the password step alone does not clear the failure count, or an attacker
        // who knows the password could reset it before every guess of the code; the second step clears it
        if (!user.isMfaEnabled()) {
            loginAttemptService.resetFailedAttempts(emailStr);
        }
        return user;
    }

    @Transactional
    public void unlockUser(String userId) {
        loginAttemptService.unlockUser(userId);
    }

    @Transactional
    public Employee registerEmployee(
            String name,
            String emailStr,
            String rawPassword,
            Designation designation,
            String department,
            int seniorityYears) {

        registrationPolicy.enforce(emailStr, department);
        Email email = new Email(emailStr);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("email", "Email address is already registered: " + emailStr);
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        String id = "EMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Employee employee = userFactory.createEmployee(id, name, emailStr, passwordHash, designation, department, seniorityYears);
        return (Employee) userRepository.save(employee);
    }

    @Transactional
    public Technician createTechnician(
            User creator,
            String name,
            String emailStr,
            String rawPassword,
            Set<Skill> skills,
            int experienceYears,
            int maxCapacity,
            String supportTeamId) {

        enforceAdminPrivileges(creator);

        Email email = new Email(emailStr);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("email", "Email address is already registered: " + emailStr);
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        String id = "TECH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Technician technician = userFactory.createTechnician(
                id, name, emailStr, passwordHash, skills, experienceYears, maxCapacity, supportTeamId);
        return (Technician) userRepository.save(technician);
    }

    @Transactional
    public TeamLead createTeamLead(
            User creator,
            String name,
            String emailStr,
            String rawPassword,
            Set<Skill> skills,
            int experienceYears,
            int maxCapacity,
            String supportTeamId) {

        enforceAdminPrivileges(creator);

        Email email = new Email(emailStr);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("email", "Email address is already registered: " + emailStr);
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        String id = "LEAD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TeamLead lead = userFactory.createTeamLead(
                id, name, emailStr, passwordHash, skills, experienceYears, maxCapacity, supportTeamId);
        return (TeamLead) userRepository.save(lead);
    }

    @Transactional
    public Administrator createAdministrator(
            User creator,
            String name,
            String emailStr,
            String rawPassword,
            AdminLevel adminLevel) {

        enforceAdminPrivileges(creator);

        Email email = new Email(emailStr);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("email", "Email address is already registered: " + emailStr);
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        String id = "ADMIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Administrator admin = userFactory.createAdministrator(id, name, emailStr, passwordHash, adminLevel);
        return (Administrator) userRepository.save(admin);
    }

    @Transactional
    public Employee updateEmployeeDesignation(String employeeId, Designation newDesignation) {
        Objects.requireNonNull(employeeId, "employeeId cannot be null");
        Objects.requireNonNull(newDesignation, "newDesignation cannot be null");

        User user = userRepository.findById(employeeId)
                .orElseThrow(() -> new ValidationException("employeeId", "Employee not found: " + employeeId));

        // OOP (Type test): the base User type has no designation, so the subtype is checked before use.
        if (!(user instanceof Employee employee)) {
            throw new ValidationException("userId", "User is not an employee: " + employeeId);
        }

        employee.setDesignation(newDesignation);
        User saved = userRepository.save(employee);
        log.info("Admin updated designation of employee '{}' to {}", employeeId, newDesignation);
        return (Employee) saved;
    }

    @Transactional(readOnly = true)
    public List<Technician> getAllTechnicians() {
        return technicianRepository.findAll();
    }

    /**
     * The technicians this staff member may see in a roster (Phase M1): an administrator sees every technician; a team
     * lead sees the members of their own support team, themselves included. A lead without a team sees nobody.
     */
    @Transactional(readOnly = true)
    public List<Technician> getRosterVisibleTo(String actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthorizedOperationException("Unknown user: " + actorId));
        if (actor instanceof Administrator) {
            return technicianRepository.findAll();
        }
        // OOP (Inheritance): a TeamLead is also a Technician, so the more specific type is tested before the general one.
        if (actor instanceof TeamLead lead) {
            String teamId = lead.getSupportTeamId();
            return technicianRepository.findAll().stream()
                    .filter(t -> teamId != null && teamId.equals(t.getSupportTeamId()))
                    .toList();
        }
        throw new UnauthorizedOperationException("Only team leads and administrators can view the technician roster");
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(String id) {
        return userRepository.findById(id);
    }

    /** Display names for these user ids, in one query; ids that match no user are absent (ADR-037). */
    @Transactional(readOnly = true)
    public Map<String, String> namesById(Collection<String> ids) {
        return ActorNames.of(userRepository, ids);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(new Email(email));
    }

    private void enforceAdminPrivileges(User actor) {
        if (actor == null || !(actor instanceof Administrator)) {
            throw new UnauthorizedOperationException("Only administrators can create technicians or administrators");
        }
    }
}

