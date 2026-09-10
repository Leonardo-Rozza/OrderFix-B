package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.AuthToken;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/** Security writers share a user-row lock and discard snapshots loaded before obtaining it. */
@Component
public class UserSecurityStateLock {
    @PersistenceContext private EntityManager entityManager;

    public void refreshAndLock(User user) {
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.getTaller() != null) entityManager.refresh(user.getTaller());
    }

    /** Called after locking the owning user, so a previous token consumer cannot be replayed. */
    public void refreshToken(AuthToken token) {
        entityManager.refresh(token);
    }
}
