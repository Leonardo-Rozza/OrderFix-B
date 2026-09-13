package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WorkshopClosureHttpConfigurationTest {
    @Test void absentOrFalseFlagRegistersNoHttpSurface(){
        for(String flag:new String[]{null,"false"})try(var context=context(flag)){
            context.refresh();assertThat(context.getBeansOfType(WorkshopClosureHttpController.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkshopClosureHttpGuardFilter.class)).isEmpty();assertThat(context.getBeansOfType(WorkshopClosureHttpExceptionHandler.class)).isEmpty();
        }
    }
    @Test void exactTrueRegistersOnlySecurityChainFilterWithoutASecondServletRegistration(){
        try(var context=context("true")){context.refresh();assertThat(context.getBeansOfType(WorkshopClosureHttpController.class)).hasSize(1);
            var filter=context.getBean(WorkshopClosureHttpGuardFilter.class);var registration=context.getBean(FilterRegistrationBean.class);
            assertThat(registration.isEnabled()).isFalse();assertThat(registration.getFilter()).isSameAs(filter);
        }
    }
    @Test void ambiguousFlagValuesFailStartupInsteadOfEnablingHttp(){
        for(String flag:new String[]{"TRUE"," true ","1","yes",""}){
            Throwable failure=catchThrowable(()->{try(var context=context(flag)){context.refresh();}});
            assertThat(failure).isNotNull();while(failure.getCause()!=null)failure=failure.getCause();
            assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private static AnnotationConfigApplicationContext context(String flag){
        var context=new AnnotationConfigApplicationContext();if(flag!=null)context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(WorkshopClosureHttpConfiguration.ENABLED_PROPERTY,flag)));
        context.registerBean(WorkshopClosureStatusService.class,()->mock(WorkshopClosureStatusService.class));
        context.registerBean(WorkshopClosureReauthenticationService.class,()->mock(WorkshopClosureReauthenticationService.class));
        context.registerBean(WorkshopClosureCommandService.class,()->mock(WorkshopClosureCommandService.class));
        context.register(WorkshopClosureHttpConfiguration.class,WorkshopClosureHttpController.class,WorkshopClosureHttpExceptionHandler.class);return context;
    }
}
