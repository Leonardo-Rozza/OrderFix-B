package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationBudget;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionResources;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionUnavailableException;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** Internal session delivery under the caller's existing budget; no registration persistence decision. */
public final class LegalRegistrationSessionIssuer {
    private final AccountSessionPolicy policy;
    private final LegalRegistrationSessionResources resources;
    private final JpaTransactionManager manager;

    LegalRegistrationSessionIssuer(AccountSessionPolicy policy, LegalRegistrationSessionResources resources,
            JpaTransactionManager manager, DataSource dataSource) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.manager = Objects.requireNonNull(manager, "manager");
        if (dataSource == null || manager.getDataSource() != dataSource
                || !(manager.getEntityManagerFactory() instanceof EntityManagerFactoryInfo factory)
                || factory.getDataSource() != dataSource) {
            throw new IllegalArgumentException("La sesión de registro requiere una única frontera JPA");
        }
    }

    public AuthResponseDto issueSession(Long userId, Long tallerId, String password, LegalRegistrationBudget owner) {
        try {
            // The scope encloses commit/rollback and resource release, including exceptional delivery.
            return resources.withinRegistrationBudget(owner, budget -> {
                var transaction = new TransactionTemplate(manager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setReadOnly(true);
                transaction.setTimeout(1 + (remainingMillis(budget) - 1) / 1_000);
                return Objects.requireNonNull(transaction.execute(status ->
                        policy.issueInCurrentTransaction(userId, tallerId, password, () -> remainingMillis(budget))));
            });
        } catch (BadCredentialsException | LegalRegistrationSessionUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new LegalRegistrationSessionUnavailableException(failure);
        }
    }

    private static int remainingMillis(LegalRegistrationBudget owner) {
        try {
            return owner.remainingMillis();
        } catch (LegalRegistrationBudget.UnavailableException failure) {
            throw new LegalRegistrationSessionUnavailableException(failure.getCause());
        }
    }
}
