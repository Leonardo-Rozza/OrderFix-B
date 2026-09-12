package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportWorkPermit;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class ExportHttpConfiguration {
    @Bean ExportHttpGuardFilter exportHttpGuardFilter(ExportWorkPermit permit) { return new ExportHttpGuardFilter(permit); }
    // Only the Spring Security chain registers this filter, immediately after JwtFilter.
    @Bean FilterRegistrationBean<ExportHttpGuardFilter> exportHttpGuardRegistration(ExportHttpGuardFilter filter) {
        var registration=new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
}
