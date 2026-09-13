package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Keeps export execution under the owning integration fixture; never changes the production worker visibility. */
public abstract class ExportSchedulingPausedTestSupport {
    @MockitoBean(name="exportJobWorker")
    ExportJobConfiguration.Worker pausedExportScheduler;
}
