package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkshopProfileErasureServiceTest {
    @Test void disabledAndInvalidTargetsDoNotAcquireAConnectionOrTransaction() {
        var jdbc=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        var disabled=new WorkshopProfileErasureService(jdbc,manager,false);
        reject(()->disabled.suppress(1,UUID.randomUUID(),UUID.randomUUID()),WorkshopProfileErasureService.Rejected.Code.DISABLED);
        var enabled=new WorkshopProfileErasureService(jdbc,manager,true);
        reject(()->enabled.suppress(0,UUID.randomUUID(),UUID.randomUUID()),WorkshopProfileErasureService.Rejected.Code.INVALID_TARGET);
        reject(()->enabled.suppress(1,null,UUID.randomUUID()),WorkshopProfileErasureService.Rejected.Code.INVALID_TARGET);
        reject(()->enabled.suppress(1,UUID.randomUUID(),null),WorkshopProfileErasureService.Rejected.Code.INVALID_TARGET);
        verifyNoInteractions(jdbc,manager);
    }
    @Test void publicDefaultCannotEnableErasureOrImportSecrets() throws Exception {
        var properties=PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"));
        assertThat(properties.getProperty(WorkshopProfileErasureService.ENABLED_PROPERTY))
                .isEqualTo("${ORDENFIX_CLOSURE_PROFILE_ERASURE_ENABLED:false}");
        assertThat(properties).doesNotContainKey("spring.config.import");
    }
    @Test void receiptCannotExposeItsIdentifiersThroughToString() {
        var receipt=new WorkshopProfileErasureService.Receipt(WorkshopProfileErasureService.Status.SUPPRESSED,
                UUID.randomUUID(),2,true,Instant.parse("2026-09-20T12:00:00Z"));
        assertThat(receipt.toString()).isEqualTo("ProfileErasureReceipt[redacted]");
    }
    @Test void receiptRejectsImpossibleCapacityAndMissingDurableEvidence() {
        for(int count:new int[]{0,-1,1_001}) assertThatThrownBy(()->new WorkshopProfileErasureService.Receipt(
                WorkshopProfileErasureService.Status.SUPPRESSED,UUID.randomUUID(),count,false,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new WorkshopProfileErasureService.Receipt(WorkshopProfileErasureService.Status.SUPPRESSED,
                null,1,false,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new WorkshopProfileErasureService.Receipt(WorkshopProfileErasureService.Status.SUPPRESSED,
                UUID.randomUUID(),1,false,null)).isInstanceOf(IllegalArgumentException.class);
    }
    private static void reject(Runnable call,WorkshopProfileErasureService.Rejected.Code code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(WorkshopProfileErasureService.Rejected.class,
                failure->assertThat(failure.code()).isEqualTo(code)).hasNoCause();
    }
}
