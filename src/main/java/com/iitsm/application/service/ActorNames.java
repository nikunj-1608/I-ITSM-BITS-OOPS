package com.iitsm.application.service;

import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The display names of the users behind a set of actor ids, read in one query (ADR-037, decision D20). The staff views
 * show the name beside the stored id; an id that belongs to nobody (the {@code SYSTEM} actor, an old e-mail actor, a
 * deleted user) is absent from the result, and the view then shows the stored text as it is.
 */
final class ActorNames {

    private ActorNames() {}

    static Map<String, String> of(UserRepository userRepository, Collection<String> actorIds) {
        Objects.requireNonNull(userRepository, "userRepository cannot be null");
        if (actorIds == null || actorIds.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = actorIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (User user : userRepository.findAllByIds(ids)) {
            names.put(user.getId(), user.getName());
        }
        // OOP (Immutability): hands back an unmodifiable copy of the names.
        return Map.copyOf(names);
    }
}
