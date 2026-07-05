package com.leonardorozza.mvgrreparacionesbackend.persistence.repository;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByTokenHashAndTipo(String tokenHash, TipoAuthToken tipo);

    /** Invalida los tokens vivos previos del usuario (pedir un link nuevo mata los anteriores). */
    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.user.id = :userId AND t.tipo = :tipo")
    void deleteByUserIdAndTipo(@Param("userId") Long userId, @Param("tipo") TipoAuthToken tipo);
}
