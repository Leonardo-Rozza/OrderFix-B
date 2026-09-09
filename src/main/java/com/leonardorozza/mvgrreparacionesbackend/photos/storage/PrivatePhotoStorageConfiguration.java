package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Opt-in server credentials. No product environment or provider endpoint comes from a request. */
@Configuration(proxyBeanMethods = false)
@Conditional(PrivatePhotoStorageConfiguration.Enabled.class)
public class PrivatePhotoStorageConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy(false)
    @ConditionalOnMissingBean(PrivatePhotoStorage.class)
    CloudinaryPrivatePhotoStorage privatePhotoStorage(Environment environment) {
        String prefix = "photos.private.cloudinary.";
        return new CloudinaryPrivatePhotoStorage(environment.getProperty(prefix + "cloud-name"),
                environment.getProperty(prefix + "api-key"), environment.getProperty(prefix + "api-secret"));
    }

    public static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String flag = context.getEnvironment().getProperty("photos.private.enabled");
            if (flag != null && !flag.equals("true") && !flag.equals("false")) {
                throw new IllegalArgumentException("El indicador de fotos privadas requiere true o false exactos.");
            }
            return "true".equals(flag);
        }
    }
}
