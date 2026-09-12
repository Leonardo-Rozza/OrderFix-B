package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExportJobConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ExportJobConfiguration.class);
    @Test void absentFlagCreatesNoJobWorkerOrKeyring() {
        runner.run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(ExportJobService.class).doesNotHaveBean(ExportArtifactCodec.class);
            assertThat(context).doesNotHaveBean(ExportJobConfiguration.Worker.class);
        });
    }
    @Test void disabledFlagDoesNotReadInvalidUnusedKeys() {
        runner.withPropertyValues("exports.jobs.enabled=false","exports.jobs.keys.v1=not-a-key")
                .run(context->assertThat(context).hasNotFailed().doesNotHaveBean(ExportJobService.class));
    }
    @ParameterizedTest @ValueSource(strings={"TRUE","yes","1",""})
    void malformedEnableFlagCannotSilentlyChooseBehavior(String value) {
        runner.withPropertyValues("exports.jobs.enabled="+value).run(context->assertThat(context).hasFailed());
    }
    @Test void enabledWithoutDedicatedKeysCannotStart() {
        dependencies(mock(JdbcTemplate.class)).withPropertyValues("exports.jobs.enabled=true")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void validOptInWiresWorkerWithoutRunningAnImmediateJob() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        dependencies(jdbc).withPropertyValues("exports.jobs.enabled=true", "exports.jobs.active-key-version=1",
                "exports.jobs.key-versions=1","exports.jobs.keys.v1="+Base64.getEncoder().encodeToString(new byte[32]))
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(ExportJobService.class).hasSingleBean(ExportJobConfiguration.Worker.class);
                    verify(jdbc).afterPropertiesSet(); verifyNoMoreInteractions(jdbc);
                });
    }
    @Test void workerDoesNotLoadAnArtifactWhileTheHttpResponseOwnsCapacity() {
        var permit=new ExportWorkPermit();
        var jobs=mock(ExportJobService.class);
        try(var worker=new ExportJobConfiguration.Worker(jobs,permit)) {
            var download=permit.tryAcquire();
            assertThat(download).isNotNull();
            worker.runPass(); verifyNoInteractions(jobs);
            download.close(); download.close();
            worker.runPass(); verify(jobs).runNext();
            try(var next=permit.tryAcquire()) {
                assertThat(next).isNotNull(); assertThat(permit.tryAcquire()).isNull();
            }
        }
    }
    @Test void failedWorkerReleasesCapacityForTheNextDownload() {
        var permit=new ExportWorkPermit(); var jobs=mock(ExportJobService.class);
        when(jobs.runNext()).thenThrow(new IllegalStateException("synthetic worker failure"));
        try(var worker=new ExportJobConfiguration.Worker(jobs,permit)) {
            worker.runPass(); verify(jobs).runNext();
            try(var download=permit.tryAcquire()) { assertThat(download).isNotNull(); }
        }
    }
    private ApplicationContextRunner dependencies(JdbcTemplate jdbc) {
        return runner.withBean(ExportWorkPermit.class,ExportWorkPermit::new).withBean(JdbcTemplate.class,()->jdbc)
                .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withBean(ExportReauthenticationService.class,()->mock(ExportReauthenticationService.class))
                .withBean(WorkshopExportSnapshotService.class,()->mock(WorkshopExportSnapshotService.class));
    }
}
