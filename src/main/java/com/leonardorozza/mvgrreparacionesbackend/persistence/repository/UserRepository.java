package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @EntityGraph(attributePaths = "taller")
    Optional<User> findByEmail(String email);

    /** Lectura de emisión de sesión por identidad durable, con estado actual del taller. */
    @EntityGraph(attributePaths = "taller")
    Optional<User> findSessionByIdAndTallerId(Long id, Long tallerId);

    boolean existsByEmail(String email);

    List<User> findAllByTallerId(Long tallerId);

    Optional<User> findByIdAndTallerId(Long id, Long tallerId);

    @EntityGraph(attributePaths = "taller")
    Optional<User> findPerfilByIdAndTallerId(Long id, Long tallerId);

    long countByTallerId(Long tallerId);
}
