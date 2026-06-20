package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAllByTallerId(Long tallerId);

    Optional<User> findByIdAndTallerId(Long id, Long tallerId);

    long countByTallerId(Long tallerId);
}
