package com.iitsm.domain.repository;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.User;
import com.iitsm.domain.model.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pattern: Repository.
 * Domain port for User hierarchy persistence and authentication lookups.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(String id);

    Optional<User> findByEmail(Email email);

    /** The users with these ids in one query (ids that match nobody are simply absent). */
    List<User> findAllByIds(Collection<String> ids);

    List<User> findByRole(UserRole role);

    List<User> findAll();

    long count();
}

