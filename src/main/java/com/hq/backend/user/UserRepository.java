package com.hq.backend.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    boolean existsByNicknameIgnoreCase(String nickname);

    Optional<User> findByEmail(String email);
}
