package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestImportService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialApplyService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialPlanService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalEditorialReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ordenfix.legal.dry-run-context.enabled=true",
        "ordenfix.legal.import-context.enabled=true",
        "ordenfix.legal.editorial-context.enabled=true"
})
class MvgrReparacionesBackendApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void normalApplicationDoesNotScanAnyLegalCliBoundaryEvenIfItsFlagsAreHostile() {
        assertThat(context.getEnvironment().getProperty(
                "ordenfix.legal.dry-run-context.enabled")).isEqualTo("true");
        assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalManifestImportService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalEditorialReadinessService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalEditorialPlanService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalEditorialApplyService.class)).isEmpty();
        assertThat(context.getBeansOfType(LegalEditorialDatabaseConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(DataSourceTransactionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcTransactionManager.class)).isEmpty();
    }

}
