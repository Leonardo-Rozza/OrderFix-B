package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkshopOperationalDeletionSchedulerConfigurationTest {
    private static final String DELETION = "ordenfix.cuenta.cierre.operational-deletion-enabled";
    private static final String WORKER = "ordenfix.cuenta.cierre.operational-worker-enabled";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkshopOperationalDeletionScheduler.class);

    @Test void missingFlagsRequireNoSchedulerOrDependencies() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WorkshopOperationalDeletionScheduler.class));
    }

    @Test void bothOptInsAreRequiredAndFalseOrUnrecognizedValuesDoNotEnableTheScheduler() {
        for (String[] values : new String[][]{
                {DELETION + "=true"}, {WORKER + "=true"},
                {DELETION + "=true", WORKER + "=false"},
                {DELETION + "=false", WORKER + "=true"},
                {DELETION + "=false", WORKER + "=false"},
                {DELETION + "=true", WORKER + "=yes"},
                {DELETION + "=1", WORKER + "=true"},
                {DELETION + "=true", WORKER + "="}}) {
            runner.withPropertyValues(values).run(context ->
                    assertThat(context).hasNotFailed().doesNotHaveBean(WorkshopOperationalDeletionScheduler.class));
        }
    }

    @Test void bothTrueRegisterOnlyOneSchedulerWithoutExecutingWork() {
        var candidates = mock(WorkshopOperationalDeletionCandidates.class);
        var worker = mock(WorkshopOperationalDeletionWorker.class);
        runner.withBean(WorkshopOperationalDeletionCandidates.class, () -> candidates)
                .withBean(WorkshopOperationalDeletionWorker.class, () -> worker)
                .withPropertyValues(DELETION + "=true", WORKER + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(WorkshopOperationalDeletionScheduler.class);
                    assertThat(context.getBean(WorkshopOperationalDeletionScheduler.class).lastRun().outcome())
                            .isEqualTo(WorkshopOperationalDeletionScheduler.Outcome.IDLE);
                    verifyNoInteractions(candidates, worker);
                });
    }
}
