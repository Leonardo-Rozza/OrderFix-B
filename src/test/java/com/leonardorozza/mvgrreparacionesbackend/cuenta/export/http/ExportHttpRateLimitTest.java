package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http.ExportHttpRequests.Operation.*;

class ExportHttpRateLimitTest {
    @Test void quotasHaveSeparateBudgetsAndReadExpiresEarlierThanPasswordAttempts() {
        var limits=new ExportHttpRateLimit(10);
        for(int i=0;i<5;i++) limits.acquire(1,ISSUE,0);
        for(int i=0;i<3;i++) {limits.acquire(1,REQUEST,0);limits.acquire(1,DOWNLOAD,0);}
        for(int i=0;i<30;i++) limits.acquire(1,READ,0);
        for(var operation:java.util.List.of(ISSUE,REQUEST,DOWNLOAD,READ)) assertThatThrownBy(()->limits.acquire(1,operation,0)).isInstanceOf(ExportHttpException.class);
        limits.acquire(1,READ,Duration.ofMinutes(1).toNanos());
        assertThatThrownBy(()->limits.acquire(1,ISSUE,Duration.ofMinutes(1).toNanos())).isInstanceOfSatisfying(ExportHttpException.class,
                failure->assertThat(failure.retryAfter).isEqualTo(840));
        limits.acquire(1,ISSUE,Duration.ofMinutes(15).toNanos());
    }
    @Test void saturationNeverEvictsAStillLimitedActorAndExpiredWindowsFreeCapacity() {
        var limits=new ExportHttpRateLimit(2);
        for(int i=0;i<5;i++) limits.acquire(1,ISSUE,0);
        limits.acquire(2,READ,0);
        assertThatThrownBy(()->limits.acquire(3,READ,0)).isInstanceOf(ExportHttpException.class);
        assertThatThrownBy(()->limits.acquire(1,ISSUE,0)).isInstanceOf(ExportHttpException.class);
        limits.acquire(3,READ,Duration.ofMinutes(1).toNanos());
        assertThatThrownBy(()->limits.acquire(1,ISSUE,Duration.ofMinutes(1).toNanos())).isInstanceOf(ExportHttpException.class);
    }
}
