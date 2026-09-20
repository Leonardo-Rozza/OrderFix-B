package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops normal startup while a restored environment is quarantined. This is an operator-supplied
 * barrier, not automatic backup detection, reconciliation or authorization to reopen an account.
 */
public final class RecoveryQuarantineEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    public static final String PROPERTY = "ordenfix.recovery.quarantine";
    private static final String BLOCKED_MESSAGE = "Inicio bloqueado por la barrera de cuarentena de recuperación.";

    @Override
    public int getOrder() {
        // ConfigData (including profile-specific files/imports) must already be available. Boot
        // still has not created its application context, DataSource, migrations, HTTP or runners.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        final String configured;
        try {
            configured = environment.getProperty(PROPERTY);
        } catch (RuntimeException invalidConfiguration) {
            // Placeholder/type errors may contain private values; never preserve their causes.
            throw blocked();
        }
        if (configured != null && !"false".equals(configured)) throw blocked();
    }

    private static StartupBlocked blocked() { return new StartupBlocked(); }

    public static final class StartupBlocked extends IllegalStateException {
        private StartupBlocked() { super(BLOCKED_MESSAGE); }
    }
}
