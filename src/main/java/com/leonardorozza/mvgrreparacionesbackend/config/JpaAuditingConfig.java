package com.leonardorozza.mvgrreparacionesbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Habilita @CreatedDate / @LastModifiedDate en las entidades auditadas.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
