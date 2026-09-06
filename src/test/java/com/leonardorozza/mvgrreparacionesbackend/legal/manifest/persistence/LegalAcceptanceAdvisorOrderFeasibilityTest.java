package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.SecurityConfig;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15A feasibility only: real Spring method-security proxies around a test-only legal advisor.
 * Does not install a production gate, HTTP matcher, database lookup, or response mapper.
 */
class LegalAcceptanceAdvisorOrderFeasibilityTest {

    private AnnotationConfigApplicationContext context;
    private Probe probe;
    private MethodGuardedController methodGuarded;
    private ClassGuardedController classGuarded;

    @BeforeEach
    void openContext() {
        SecurityContextHolder.clearContext();
        context = new AnnotationConfigApplicationContext(FeasibilityConfiguration.class);
        probe = context.getBean(Probe.class);
        methodGuarded = context.getBean(MethodGuardedController.class);
        classGuarded = context.getBean(ClassGuardedController.class);
    }

    @AfterEach
    void closeContext() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void localMethodSecurityConfigurationAndActualAdvisorOrdersMatchTheCandidate() {
        EnableMethodSecurity current = SecurityConfig.class.getAnnotation(EnableMethodSecurity.class);
        assertNotNull(current);
        assertTrue(current.prePostEnabled());
        assertFalse(current.securedEnabled());
        assertFalse(current.jsr250Enabled());
        assertEquals(0, current.offset());
        assertEquals(401, candidateOrder());

        Map<String, Integer> orders = new TreeMap<>();
        context.getBeansOfType(Advisor.class).forEach((name, advisor) -> {
            assertInstanceOf(Ordered.class, advisor, name);
            orders.put(name, ((Ordered) advisor).getOrder());
        });
        // Spring exposes both interceptor and advisor-wrapper beans as Advisor instances.
        // Their registry entries are distinct; the effective proxy chain below must remain singular.
        Map.of(
                "preFilterAuthorization", 100,
                "preAuthorizeAuthorization", 200,
                "authorizeReturnObject", 450,
                "postAuthorizeAuthorization", 500,
                "postFilterAuthorization", 600).forEach((prefix, expectedOrder) -> {
            assertEquals(expectedOrder, orders.get(prefix + "Advisor"), prefix + "Advisor");
            assertEquals(expectedOrder, orders.get(prefix + "MethodInterceptor"),
                    prefix + "MethodInterceptor");
        });
        assertEquals(Integer.valueOf(401), orders.get("legalStandInAdvisor"));
        assertEquals(List.of(200, 401), chainOrders(methodGuarded));
        assertEquals(List.of(200, 401), chainOrders(classGuarded));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedUserNeverReachesLegalLookupOrBusinessEvenWhenLegalIsUnavailable(boolean unavailable) {
        authenticate("USER");
        probe.unavailable = unavailable;

        assertThrows(AccessDeniedException.class, methodGuarded::adminAction);
        assertEquals(List.of(), probe.events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExemptionNeverBypassesClassLevelAdminAuthorization(boolean unavailable) {
        authenticate("USER");
        probe.unavailable = unavailable;

        assertThrows(AccessDeniedException.class, classGuarded::exemptAdminAction);
        assertEquals(List.of(), probe.events);
    }

    @Test
    void authorizedAdminReachesLegalAdvisorBeforeBusiness() {
        authenticate("ADMIN");

        assertEquals("business", methodGuarded.adminAction());
        assertEquals(List.of("legal-lookup", "business"), probe.events);
    }

    @Test
    void legalFailureForAuthorizedAdminPreventsBusiness() {
        authenticate("ADMIN");
        probe.unavailable = true;

        assertThrows(LegalStandInUnavailableException.class, methodGuarded::adminAction);
        assertEquals(List.of("legal-lookup"), probe.events);
    }

    @Test
    void authorizedExemptAdminCanReachBusinessWithoutLookingUpUnavailableLegalState() {
        authenticate("ADMIN");
        probe.unavailable = true;

        assertEquals("exempt-business", classGuarded.exemptAdminAction());
        assertEquals(List.of("legal-exemption", "exempt-business"), probe.events);
    }

    private static int candidateOrder() {
        return AuthorizationInterceptorsOrder.JSR250.getOrder() + 1;
    }

    private static List<Integer> chainOrders(Object proxy) {
        assertInstanceOf(Advised.class, proxy);
        return Arrays.stream(((Advised) proxy).getAdvisors()).map(advisor -> {
            assertInstanceOf(Ordered.class, advisor);
            return ((Ordered) advisor).getOrder();
        }).toList();
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("feasibility-actor", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class FeasibilityConfiguration {
        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        MethodGuardedController methodGuardedController(Probe probe) {
            return new MethodGuardedController(probe);
        }

        @Bean
        ClassGuardedController classGuardedController(Probe probe) {
            return new ClassGuardedController(probe);
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        DefaultPointcutAdvisor legalStandInAdvisor(Probe probe) {
            StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
                @Override
                public boolean matches(Method method, Class<?> targetClass) {
                    return (targetClass == MethodGuardedController.class
                            || targetClass == ClassGuardedController.class)
                            && method.isAnnotationPresent(GetMapping.class);
                }
            };
            MethodInterceptor interceptor = invocation -> {
                // Deliberately a test-only method-name exemption; no claim about future HTTP matching.
                if (invocation.getMethod().getName().equals("exemptAdminAction")) {
                    probe.events.add("legal-exemption");
                } else {
                    probe.events.add("legal-lookup");
                    if (probe.unavailable) {
                        throw new LegalStandInUnavailableException();
                    }
                }
                return invocation.proceed();
            };
            DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
            advisor.setOrder(candidateOrder());
            return advisor;
        }
    }

    static class Probe {
        private final List<String> events = new ArrayList<>();
        private boolean unavailable;
    }

    @RestController
    public static class MethodGuardedController {
        private final Probe probe;

        MethodGuardedController(Probe probe) {
            this.probe = probe;
        }

        @GetMapping("/feasibility/admin")
        @PreAuthorize("hasRole('ADMIN')")
        public String adminAction() {
            probe.events.add("business");
            return "business";
        }
    }

    @RestController
    @PreAuthorize("hasRole('ADMIN')")
    public static class ClassGuardedController {
        private final Probe probe;

        ClassGuardedController(Probe probe) {
            this.probe = probe;
        }

        @GetMapping("/feasibility/admin-exempt")
        public String exemptAdminAction() {
            probe.events.add("exempt-business");
            return "exempt-business";
        }
    }

    private static class LegalStandInUnavailableException extends RuntimeException {
    }
}
