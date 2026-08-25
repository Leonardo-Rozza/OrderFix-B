package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalManifestDryRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.support.JdbcTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "ordenfix.legal.dry-run-context.enabled=true")
class MvgrReparacionesBackendApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void normalApplicationDoesNotScanTheLegalDryRunBoundaryEvenIfItsFlagIsHostile() {
        assertThat(context.getEnvironment().getProperty(
                "ordenfix.legal.dry-run-context.enabled")).isEqualTo("true");
        assertThat(context.getBeansOfType(LegalManifestDryRunService.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcTransactionManager.class)).isEmpty();
    }

}
