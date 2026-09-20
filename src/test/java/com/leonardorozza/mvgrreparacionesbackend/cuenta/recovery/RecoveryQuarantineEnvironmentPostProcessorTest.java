package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryQuarantineEnvironmentPostProcessorTest {
    private final RecoveryQuarantineEnvironmentPostProcessor barrier = new RecoveryQuarantineEnvironmentPostProcessor();

    @ParameterizedTest @NullSource @ValueSource(strings = "false")
    void onlyAbsentOrExactFalsePreservesStartup(String value) {
        var environment = new MockEnvironment();
        if (value != null) environment.setProperty(RecoveryQuarantineEnvironmentPostProcessor.PROPERTY, value);
        assertThatCode(() -> barrier.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "FALSE", "True", "False", " false", "false ", " true ", "", "0", "1", "yes", "no", "private-value-do-not-log"})
    void quarantineAndMalformedValuesFailClosedWithoutEchoingConfiguration(String value) {
        var environment = new MockEnvironment().withProperty(RecoveryQuarantineEnvironmentPostProcessor.PROPERTY, value);
        assertThatThrownBy(() -> barrier.postProcessEnvironment(environment, null))
                .isInstanceOf(RecoveryQuarantineEnvironmentPostProcessor.StartupBlocked.class)
                .hasMessage("Inicio bloqueado por la barrera de cuarentena de recuperación.")
                .hasNoCause();
    }

    @Test void propertyResolutionFailureIsSanitizedWithoutNestedConfigurationDetails() {
        var environment = mock(ConfigurableEnvironment.class);
        when(environment.getProperty(RecoveryQuarantineEnvironmentPostProcessor.PROPERTY))
                .thenThrow(new IllegalArgumentException("private-jdbc-password-and-placeholder"));
        assertThatThrownBy(() -> barrier.postProcessEnvironment(environment, null))
                .isInstanceOf(RecoveryQuarantineEnvironmentPostProcessor.StartupBlocked.class)
                .hasNoCause().hasMessageNotContaining("private-jdbc-password-and-placeholder");
    }

    @Test void runsAfterConfigDataSoTheMarkerCanComeFromTheRecoveryConfigurationFile() {
        assertThat(barrier.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER)
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
