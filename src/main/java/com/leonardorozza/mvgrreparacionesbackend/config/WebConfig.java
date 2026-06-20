package com.leonardorozza.mvgrreparacionesbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Habilita la resolución de {@code Pageable} desde los query params (?page=&size=&sort=)
 * y serializa los {@code Page} con un formato estable (content + page metadata),
 * evitando el warning de serialización inestable de PageImpl.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig {
}
