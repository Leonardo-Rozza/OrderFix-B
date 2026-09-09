package com.leonardorozza.mvgrreparacionesbackend.photos;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivatePhotoOperations;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="photos.private.enabled",havingValue="true")
public class PrivatePhotoConfiguration {
    @Bean(destroyMethod="close")
    PrivatePhotoService privatePhotoService(Environment environment, PrivatePhotoStorage storage) {
        return LegalPrivatePhotoOperations.open(environment,storage);
    }
    @Bean Cleanup privatePhotoCleanup(PrivatePhotoService service) { return new Cleanup(service); }
    static final class Cleanup {
        private final PrivatePhotoService service;
        Cleanup(PrivatePhotoService service) { this.service=service; }
        @Scheduled(fixedDelay=60_000,initialDelay=60_000)
        void run() {
            // Each pass is bounded and preserves pending identities for the next attempt.
            try { service.cleanup(); } catch(RuntimeException failure) { /* no provider diagnostics in logs */ }
        }
    }
}
