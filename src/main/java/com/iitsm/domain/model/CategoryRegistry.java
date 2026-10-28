package com.iitsm.domain.model;

import com.iitsm.domain.exception.ValidationException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pattern: Registry / Open-Closed Principle.
 * Allows adding new categories and domain prefixes at runtime via registration
 * without modifying existing enums or code.
 */
public class CategoryRegistry {

    // ConcurrentHashMaps chosen for thread-safe dynamic registry reads and runtime additions
    private final Map<String, String> registeredCategories = new ConcurrentHashMap<>();
    private final Map<String, String> registeredPrefixes = new ConcurrentHashMap<>();
    private final Map<String, String> prefixToDomain = new ConcurrentHashMap<>();

    public CategoryRegistry() {
        // Register all built-in enum categories and fixed domain prefixes (Examples from specification)
        register(IncidentCategory.NETWORK.name(), IncidentCategory.NETWORK.getDisplayName(), "NET");
        register(IncidentCategory.SOFTWARE.name(), IncidentCategory.SOFTWARE.getDisplayName(), "SOF");
        register(IncidentCategory.HARDWARE.name(), IncidentCategory.HARDWARE.getDisplayName(), "HWD");
        register(IncidentCategory.DATABASE.name(), IncidentCategory.DATABASE.getDisplayName(), "DBS");
        register(IncidentCategory.SECURITY.name(), IncidentCategory.SECURITY.getDisplayName(), "SEC");
        register(IncidentCategory.CLOUD.name(), IncidentCategory.CLOUD.getDisplayName(), "CLD");
        register(IncidentCategory.AUTHENTICATION.name(), IncidentCategory.AUTHENTICATION.getDisplayName(), "AUT");
        register(IncidentCategory.APPLICATION.name(), IncidentCategory.APPLICATION.getDisplayName(), "APP");
        register(IncidentCategory.STORAGE.name(), IncidentCategory.STORAGE.getDisplayName(), "STG");

        // Supported website and system domains
        register("ACCESS", "Access Management", "ACC");
        register("GENERAL", "General IT", "GEN");
    }

    public void register(String code, String displayName) {
        if (code == null || code.trim().isEmpty()) {
            throw new ValidationException("categoryCode", "Category code cannot be null or blank");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new ValidationException("displayName", "Display name cannot be null or blank");
        }
        String normalizedCode = code.trim().toUpperCase();
        String derivedPrefix = deriveUniquePrefix(normalizedCode);
        register(normalizedCode, displayName, derivedPrefix);
    }

    public void register(String code, String displayName, String prefix) {
        if (code == null || code.trim().isEmpty()) {
            throw new ValidationException("categoryCode", "Category code cannot be null or blank");
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new ValidationException("displayName", "Display name cannot be null or blank");
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new ValidationException("prefix", "Prefix cannot be null or blank");
        }

        String normalizedCode = code.trim().toUpperCase();
        String normalizedPrefix = prefix.trim().toUpperCase();

        // Enforce Requirement 1: different domains must have different prefixes
        String existingDomainForPrefix = prefixToDomain.get(normalizedPrefix);
        if (existingDomainForPrefix != null && !existingDomainForPrefix.equals(normalizedCode)) {
            throw new ValidationException("prefix",
                    "Prefix " + normalizedPrefix + " is already assigned to domain: " + existingDomainForPrefix);
        }

        // If this domain already had a different prefix, release it
        String oldPrefix = registeredPrefixes.get(normalizedCode);
        if (oldPrefix != null && !oldPrefix.equals(normalizedPrefix)) {
            prefixToDomain.remove(oldPrefix);
        }

        registeredCategories.put(normalizedCode, displayName.trim());
        registeredPrefixes.put(normalizedCode, normalizedPrefix);
        prefixToDomain.put(normalizedPrefix, normalizedCode);
    }

    public String getPrefix(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "GEN";
        }
        String normalized = code.trim().toUpperCase();
        String prefix = registeredPrefixes.get(normalized);
        if (prefix != null) {
            return prefix;
        }

        // Check if code matches any enum category displayName or alias
        for (IncidentCategory cat : IncidentCategory.values()) {
            if (cat.name().equalsIgnoreCase(normalized) || cat.getDisplayName().equalsIgnoreCase(normalized)) {
                return registeredPrefixes.getOrDefault(cat.name(), "GEN");
            }
        }

        // Dynamically assign and cache a unique prefix for unknown domain
        String derived = deriveUniquePrefix(normalized);
        register(normalized, code.trim(), derived);
        return derived;
    }

    public String getPrefix(IncidentCategory category) {
        if (category == null) {
            return "GEN";
        }
        return getPrefix(category.name());
    }

    private String deriveUniquePrefix(String code) {
        String clean = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (clean.length() >= 3) {
            String candidate = clean.substring(0, 3);
            if (!prefixToDomain.containsKey(candidate) || prefixToDomain.get(candidate).equals(code)) {
                return candidate;
            }
        }
        // If 3-letter prefix taken, find first available variation
        for (int i = 0; i < clean.length(); i++) {
            for (int j = i + 1; j < clean.length(); j++) {
                for (int k = j + 1; k < clean.length(); k++) {
                    String candidate = "" + clean.charAt(i) + clean.charAt(j) + clean.charAt(k);
                    if (!prefixToDomain.containsKey(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        // Fallback with numbers if necessary
        for (int n = 1; n <= 99; n++) {
            String candidate = (clean.length() >= 2 ? clean.substring(0, 2) : "D") + n;
            if (!prefixToDomain.containsKey(candidate)) {
                return candidate;
            }
        }
        return "GEN";
    }

    public boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        return registeredCategories.containsKey(code.trim().toUpperCase());
    }

    public String getDisplayName(String code) {
        if (code == null) {
            return null;
        }
        return registeredCategories.get(code.trim().toUpperCase());
    }

    public Set<String> getAllCodes() {
        return Collections.unmodifiableSet(registeredCategories.keySet());
    }

    public Map<String, String> getAllCategories() {
        return Collections.unmodifiableMap(registeredCategories);
    }

    public Map<String, String> getAllPrefixes() {
        return Collections.unmodifiableMap(registeredPrefixes);
    }
}

