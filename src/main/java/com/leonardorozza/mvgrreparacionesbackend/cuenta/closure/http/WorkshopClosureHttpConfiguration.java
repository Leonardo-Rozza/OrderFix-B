package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration(proxyBeanMethods=false)
@Conditional(WorkshopClosureHttpConfiguration.Enabled.class)
public class WorkshopClosureHttpConfiguration {
    public static final String ENABLED_PROPERTY="ordenfix.cuenta.cierre.http-enabled";
    @Bean WorkshopClosureHttpGuardFilter workshopClosureHttpGuardFilter() { return new WorkshopClosureHttpGuardFilter(); }
    /** Registered only by SecurityConfig immediately after JWT, never as a second servlet filter. */
    @Bean FilterRegistrationBean<WorkshopClosureHttpGuardFilter> workshopClosureHttpGuardRegistration(WorkshopClosureHttpGuardFilter filter) {
        var registration=new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    public static final class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            String value=context.getEnvironment().getProperty(ENABLED_PROPERTY);
            if(value==null || "false".equals(value)) return false;
            if(!"true".equals(value)) throw new IllegalArgumentException("El flag HTTP de cierre requiere true o false exactos.");
            return true;
        }
    }
}
