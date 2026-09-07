package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import jakarta.servlet.Filter;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.filters.RemoteIpFilter;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

/** Rejects known peer rewriters in the supported embedded Tomcat runtime before serving requests. */
@Configuration(proxyBeanMethods = false)
@Conditional(LegalAcceptanceHttpConfiguration.Enabled.class)
public class LegalAcceptancePeerConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> legalAcceptancePeerGuard() {
        return factory -> factory.addContextCustomizers(context -> context.addLifecycleListener(event -> {
            // Tomcat 11 emits START after SCIs and filterStart. Boot 4.0 removes connectors during
            // this event and restores them only in WebServer.start, after initialization succeeds.
            if (event.getLifecycle() == context && Lifecycle.START_EVENT.equals(event.getType())) {
                requireOriginalPeer(context);
            }
        }));
    }

    static void requireOriginalPeer(Context context) {
        try {
            Container host = context.getParent();
            if (!(host instanceof Host) || !(host.getParent() instanceof Engine engine)) throw invalid();
            requireNoRemoteIpValve(context);
            requireNoRemoteIpValve(host);
            requireNoRemoteIpValve(engine);
            for (FilterDef definition : context.findFilterDefs()) {
                if (definition == null) throw invalid();
                Filter filter = definition.getFilter();
                Class<?> type;
                if (filter != null) {
                    type = filter.getClass();
                } else {
                    // Inspect a class definition without creating another filter or executing its initializer.
                    String name = definition.getFilterClass();
                    if (name == null || name.isEmpty() || context.getLoader() == null) throw invalid();
                    type = Class.forName(name, false, context.getLoader().getClassLoader());
                }
                if (!Filter.class.isAssignableFrom(type) || ForwardedHeaderFilter.class.isAssignableFrom(type)
                        || RemoteIpFilter.class.isAssignableFrom(type)) throw invalid();
            }
        } catch (RuntimeException | LinkageError | ClassNotFoundException failure) {
            // Runtime filter definitions and classloading failures can carry external configuration text.
            throw invalid();
        }
    }

    private static void requireNoRemoteIpValve(Container container) {
        for (var valve : container.getPipeline().getValves()) {
            if (valve == null || valve instanceof RemoteIpValve) throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("La captura legal requiere el peer original del servidor.");
    }
}
