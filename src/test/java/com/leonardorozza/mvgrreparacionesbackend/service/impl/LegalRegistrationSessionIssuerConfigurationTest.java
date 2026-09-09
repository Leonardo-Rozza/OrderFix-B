package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationBudget;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionDataSourceConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionResources;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.AuthResponseDto;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Explicit module parsing and factory wiring; physical Boot/JPA lifecycle is covered by the IT. */
class LegalRegistrationSessionIssuerConfigurationTest {
    private static final String ISSUER = "legalRegistrationSessionIssuer";
    private static final String READY = "legalRegistrationSessionWiringReady";

    @Test
    void compositionIsExplicitAndItsSingleIssuerBeanWaitsForTheNominalGate() throws Exception {
        Class<?> configuration = LegalRegistrationSessionIssuerConfiguration.class;
        assertThat(Modifier.isPublic(configuration.getModifiers())).isTrue();
        assertThat(AnnotatedElementUtils.hasAnnotation(configuration, Component.class)).isFalse();
        assertThat(configuration.getAnnotation(Import.class).value()).containsExactly(LegalRegistrationSessionDataSourceConfiguration.class);
        var methods = Arrays.stream(configuration.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Bean.class)).toList();
        assertThat(methods).hasSize(1);
        var bean = methods.getFirst();
        assertThat(bean.getName()).isEqualTo(ISSUER);
        assertThat(bean.getReturnType()).isEqualTo(LegalRegistrationSessionIssuer.class);
        assertThat(bean.getParameterTypes()).containsExactly(AccountSessionPolicy.class,
                LegalRegistrationSessionResources.class, PlatformTransactionManager.class, DataSource.class);
        assertThat(bean.getAnnotation(DependsOn.class).value()).containsExactly(READY);
        assertThat(bean.getAnnotation(Lazy.class).value()).isFalse();
        assertThat(bean.getParameters()[2].getAnnotation(Qualifier.class).value()).isEqualTo("transactionManager");
        assertThat(bean.getParameters()[3].getAnnotation(Qualifier.class).value()).isEqualTo("dataSource");
        assertThat(AnnotatedElementUtils.hasAnnotation(LegalRegistrationSessionIssuer.class, Component.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(LegalRegistrationSessionIssuer.class, Transactional.class)).isFalse();
        assertThat(Arrays.stream(LegalRegistrationSessionIssuer.class.getDeclaredMethods()))
                .allSatisfy(method -> assertThat(method.isAnnotationPresent(Transactional.class)).isFalse());
    }

    @Test
    void springParsesTheExplicitImportAndGateDependencyWithoutCreatingRuntimeResources() {
        var inspected = new AtomicBoolean();
        var stopBeforeInstantiation = new IllegalStateException("synthetic metadata inspection complete");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(LegalRegistrationSessionIssuerConfiguration.class);
            context.addBeanFactoryPostProcessor(factory -> {
                assertThat(factory.getBeanDefinitionNames()).contains(ISSUER, READY,
                        "legalRegistrationSessionResources", "legalRegistrationSessionRoutingPostProcessor");
                assertThat(factory.getBeanDefinition(ISSUER).getDependsOn()).containsExactly(READY);
                assertThat(factory.getBeanDefinition(ISSUER).isLazyInit()).isFalse();
                assertThat(factory.containsSingleton(ISSUER)).isFalse();
                assertThat(factory.containsSingleton("legalRegistrationSessionResources")).isFalse();
                assertThat(factory.containsSingleton("legalRegistrationSessionRoutingPostProcessor")).isFalse();
                assertThat(factory.containsBeanDefinition("dataSource")).isFalse();
                inspected.set(true);
                throw stopBeforeInstantiation;
            });

            assertThatThrownBy(context::refresh).isSameAs(stopBeforeInstantiation);

            assertThat(inspected).isTrue();
            assertThat(context.isActive()).isFalse();
        }
    }

    @Test
    void theBeanFactoryUsesTheProvidedPolicyResourcesAndMatchingJpaIdentity() {
        var policy = mock(AccountSessionPolicy.class);
        var resources = mock(LegalRegistrationSessionResources.class);
        var manager = mock(JpaTransactionManager.class);
        var source = mock(DataSource.class);
        var factory = mock(EntityManagerFactory.class, withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
        when(manager.getDataSource()).thenReturn(source);
        when(manager.getEntityManagerFactory()).thenReturn(factory);
        when(((EntityManagerFactoryInfo) factory).getDataSource()).thenReturn(source);

        var issuer = new LegalRegistrationSessionIssuerConfiguration().legalRegistrationSessionIssuer(policy, resources, manager, source);

        assertThat(issuer).isNotNull();
        verifyNoInteractions(policy, resources, source);
        var status = new SimpleTransactionStatus();
        var response = new AuthResponseDto("synthetic-jwt", "Bearer", "current@example.test", true);
        var owner = LegalRegistrationBudget.start();
        when(manager.getTransaction(any())).thenReturn(status);
        when(policy.issueInCurrentTransaction(eq(41L), eq(7L), eq("synthetic-password"), any())).thenReturn(response);
        when(resources.withinRegistrationBudget(eq(owner), any())).thenAnswer(call -> {
            Function<LegalRegistrationBudget, AuthResponseDto> work = call.getArgument(1);
            return work.apply(owner);
        });

        assertThat(issuer.issueSession(41L, 7L, "synthetic-password", owner)).isSameAs(response);

        verify(resources).withinRegistrationBudget(eq(owner), any());
        verify(policy).issueInCurrentTransaction(eq(41L), eq(7L), eq("synthetic-password"), any());
        verify(manager).commit(status);
    }

    @Test
    void theBeanFactoryRejectsANominalTransactionManagerThatIsNotJpaBeforeUsingOtherDependencies() {
        var policy = mock(AccountSessionPolicy.class);
        var resources = mock(LegalRegistrationSessionResources.class);
        var manager = mock(PlatformTransactionManager.class);
        var source = mock(DataSource.class);

        assertThatThrownBy(() -> new LegalRegistrationSessionIssuerConfiguration()
                .legalRegistrationSessionIssuer(policy, resources, manager, source))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("La sesión de registro requiere un gestor JPA")
                .hasNoCause();

        verifyNoInteractions(policy, resources, manager, source);
    }
}
