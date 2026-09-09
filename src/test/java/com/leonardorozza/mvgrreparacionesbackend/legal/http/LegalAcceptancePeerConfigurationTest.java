package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import jakarta.servlet.Filter;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Loader;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.filters.RemoteIpFilter;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalAcceptancePeerConfigurationTest {

    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void disabledGuardHasNoCustomizerAndNeedsNoSecretsOrServer(String flag) {
        try (var application = new AnnotationConfigApplicationContext()) {
            var environment = new MockEnvironment();
            if (!flag.equals("absent")) environment.setProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, flag);
            application.setEnvironment(environment);
            application.register(LegalAcceptancePeerConfiguration.class);
            application.refresh();
            assertThat(application.getBeansOfType(LegalAcceptancePeerConfiguration.class)).isEmpty();
            assertThat(application.getBeansOfType(WebServerFactoryCustomizer.class)).isEmpty();
        }
    }

    @Test void registrationAloneReusesThePeerGuardWithoutPrivateAcceptanceOrReader() {
        try (var application = new AnnotationConfigApplicationContext()) {
            application.setEnvironment(new MockEnvironment()
                    .withProperty(LegalRegistrationHttpConfiguration.CONSENT_PROPERTY, "true"));
            application.register(LegalAcceptancePeerConfiguration.class);
            application.refresh();
            assertThat(application.getBeansOfType(WebServerFactoryCustomizer.class)).hasSize(1);
            assertThat(application.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
        }
    }

    @Test void enabledGuardPublishesOnlyItsTomcatCustomizerWithoutOpeningResources() {
        try (var application = new AnnotationConfigApplicationContext()) {
            application.setEnvironment(new MockEnvironment()
                    .withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, "true")
                    .withProperty("ordenfix.legal.account-read.enabled", "true"));
            application.register(LegalAcceptancePeerConfiguration.class);
            application.refresh();
            assertThat(application.getBeansOfType(WebServerFactoryCustomizer.class)).hasSize(1);
            assertThat(application.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            assertThat(application.getBeansOfType(LegalRequestMetadataResolver.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TRUE", "1", " true", ""})
    void malformedAcceptanceFlagCannotSilentlySkipTheGuard(String flag) {
        try (var application = new AnnotationConfigApplicationContext()) {
            application.setEnvironment(new MockEnvironment()
                    .withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, flag));
            assertThatThrownBy(() -> { application.register(LegalAcceptancePeerConfiguration.class); application.refresh(); })
                    .hasStackTraceContaining("true o false exactos");
        }
    }

    @Test void enabledGuardStillRequiresThePrivateReaderFlag() {
        try (var application = new AnnotationConfigApplicationContext()) {
            application.setEnvironment(new MockEnvironment()
                    .withProperty(LegalAcceptanceHttpConfiguration.ENABLED_PROPERTY, "true"));
            assertThatThrownBy(() -> { application.register(LegalAcceptancePeerConfiguration.class); application.refresh(); })
                    .hasStackTraceContaining("account-read=true");
        }
    }

    @Test void normalTomcatGraphAndAnInnocuousFilterRemainValid() {
        var context = context();
        addFilter(context, new CharacterEncodingFilter(), null);
        assertThatCode(() -> LegalAcceptancePeerConfiguration.requireOriginalPeer(context)).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(strings = {"context", "host", "engine", "subclass"})
    void everyRelevantPipelineRejectsTheNativePeerRewriter(String target) {
        var context = context();
        var container = switch (target) {
            case "host" -> context.getParent();
            case "engine" -> context.getParent().getParent();
            default -> context;
        };
        container.getPipeline().addValve(target.equals("subclass") ? new DerivedRemoteIpValve() : new RemoteIpValve());
        assertRejected(context);
    }

    @ParameterizedTest @ValueSource(strings = {"forwarded", "remote", "forwarded-subclass", "remote-subclass", "remove-only"})
    void actualFilterInstancesRejectKnownRewritersIncludingSubclasses(String variant) {
        var context = context();
        Filter filter = switch (variant) {
            case "remote" -> new RemoteIpFilter();
            case "remote-subclass" -> new DerivedRemoteIpFilter();
            case "forwarded-subclass" -> new DerivedForwardedHeaderFilter();
            case "remove-only" -> { var forwarded = new ForwardedHeaderFilter(); forwarded.setRemoveOnly(true); yield forwarded; }
            default -> new ForwardedHeaderFilter();
        };
        addFilter(context, filter, null);
        assertRejected(context);
    }

    @ParameterizedTest @ValueSource(strings = {"forwarded", "remote", "forwarded-subclass", "remote-subclass"})
    void classDefinitionsCannotHideARewriterFromTheStartupCheck(String variant) {
        var context = context();
        Class<?> type = switch (variant) {
            case "remote" -> RemoteIpFilter.class;
            case "remote-subclass" -> DerivedRemoteIpFilter.class;
            case "forwarded-subclass" -> DerivedForwardedHeaderFilter.class;
            default -> ForwardedHeaderFilter.class;
        };
        addFilter(context, null, type.getName());
        assertRejected(context);
    }

    @Test void innocuousClassDefinitionsAreInspectedWithoutInitialization() {
        var context = context();
        InitializationProbe.initialized = false;
        addFilter(context, null, LazyInnocuousFilter.class.getName());
        assertThatCode(() -> LegalAcceptancePeerConfiguration.requireOriginalPeer(context)).doesNotThrowAnyException();
        assertThat(InitializationProbe.initialized).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "wrong-type", "no-class"})
    void unaccreditableFilterDefinitionsFailClosedWithoutLeakingTheirNames(String variant) {
        var context = context();
        String className = switch (variant) {
            case "missing" -> "synthetic.private.configuration.SecretClass";
            case "wrong-type" -> String.class.getName();
            default -> null;
        };
        addFilter(context, null, className);
        assertRejected(context);
    }

    @Test void missingContainerAncestryFailsClosed() {
        assertRejected(new StandardContext());
    }

    @Test void lifecycleCheckWaitsForRegisteredFiltersAndRunsOnTheOwningContextStart() {
        var factory = mock(TomcatServletWebServerFactory.class);
        new LegalAcceptancePeerConfiguration().legalAcceptancePeerGuard().customize(factory);
        var customizer = ArgumentCaptor.forClass(TomcatContextCustomizer.class);
        verify(factory).addContextCustomizers(customizer.capture());
        var context = context();
        var existingListeners = context.findLifecycleListeners();
        customizer.getValue().customize(context);
        var listeners = Arrays.stream(context.findLifecycleListeners())
                .filter(candidate -> Arrays.stream(existingListeners).noneMatch(existing -> existing == candidate))
                .toArray(LifecycleListener[]::new);
        assertThat(listeners).hasSize(1);
        assertThatCode(() -> listeners[0].lifecycleEvent(new LifecycleEvent(context, Lifecycle.BEFORE_START_EVENT, null)))
                .doesNotThrowAnyException();
        addFilter(context, new ForwardedHeaderFilter(), null);
        assertThatCode(() -> listeners[0].lifecycleEvent(new LifecycleEvent(context(), Lifecycle.START_EVENT, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> listeners[0].lifecycleEvent(new LifecycleEvent(context, Lifecycle.START_EVENT, null)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    private static StandardContext context() {
        var engine = new StandardEngine(); engine.setName("synthetic-engine");
        var host = new StandardHost(); host.setName("localhost"); engine.addChild(host);
        var context = new StandardContext(); context.setName(""); context.setPath(""); host.addChild(context);
        var loader = mock(Loader.class);
        when(loader.getClassLoader()).thenReturn(LegalAcceptancePeerConfigurationTest.class.getClassLoader());
        context.setLoader(loader);
        return context;
    }

    private static void addFilter(Context context, Filter filter, String className) {
        var definition = new FilterDef(); definition.setFilterName("synthetic-private-filter");
        if (filter != null) definition.setFilter(filter);
        if (className != null) definition.setFilterClass(className);
        context.addFilterDef(definition);
    }

    private static void assertRejected(Context context) {
        assertThatThrownBy(() -> LegalAcceptancePeerConfiguration.requireOriginalPeer(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("La captura legal requiere el peer original del servidor.")
                .hasNoCause().hasStackTraceContaining("LegalAcceptancePeerConfiguration")
                .hasMessageNotContaining("synthetic").hasMessageNotContaining("SecretClass");
    }

    static class DerivedRemoteIpValve extends RemoteIpValve { }
    static class DerivedRemoteIpFilter extends RemoteIpFilter { }
    static class DerivedForwardedHeaderFilter extends ForwardedHeaderFilter { }
    static class InitializationProbe { static boolean initialized; }
    static class LazyInnocuousFilter extends CharacterEncodingFilter {
        static { InitializationProbe.initialized = true; }
    }
}
