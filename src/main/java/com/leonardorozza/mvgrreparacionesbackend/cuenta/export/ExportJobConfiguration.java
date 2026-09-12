package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in: no key generation, external side effects, or jobs enabled by a default environment. */
@Configuration(proxyBeanMethods=false)
@Conditional(ExportJobConfiguration.Enabled.class)
public class ExportJobConfiguration {
    @Bean ExportArtifactCodec exportArtifactCodec(Environment env) {
        try {
            int active=Integer.parseInt(env.getRequiredProperty("exports.jobs.active-key-version"));
            String[] versions=env.getRequiredProperty("exports.jobs.key-versions").split(",",-1);
            if(versions.length==0 || versions.length>8) throw new IllegalArgumentException();
            Map<Integer,String> keys=new HashMap<>();
            for(String value:versions) {
                if(!value.matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException();
                int version=Integer.parseInt(value);
                if(keys.put(version,env.getRequiredProperty("exports.jobs.keys.v"+version))!=null) throw new IllegalArgumentException();
            }
            return new ExportArtifactCodec(keys,active);
        } catch(RuntimeException failure) { throw new IllegalArgumentException("La configuración de exportaciones es inválida."); }
    }
    @Bean ExportJobService exportJobService(JdbcTemplate jdbc,PlatformTransactionManager manager,ExportReauthenticationService reauth,
            WorkshopExportSnapshotService snapshots,ExportArtifactCodec codec,ObjectProvider<PrivatePhotoService> photos) {
        return new ExportJobService(jdbc,manager,reauth,snapshots,codec,new ExportPhotoReader(photos::getIfAvailable));
    }
    @Bean(destroyMethod="close") Worker exportJobWorker(ExportJobService service,ExportWorkPermit permit) { return new Worker(service,permit); }
    static final class Worker implements AutoCloseable {
        private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(runnable->{
            Thread thread=new Thread(runnable,"ordenfix-export-worker"); thread.setDaemon(true); return thread;
        });
        private final ExportJobService service;
        private final ExportWorkPermit permit;
        Worker(ExportJobService service,ExportWorkPermit permit) {
            this.service=service; this.permit=permit;
            executor.scheduleWithFixedDelay(this::runPass,60,60,TimeUnit.SECONDS);
        }
        void runPass() {
            try (var lease=permit.tryAcquire()) {
                if(lease==null) return;
                try { service.runNext(); } catch(RuntimeException failure) { /* lease recovery on the next bounded pass; never log payload/provider diagnostics */ }
            }
        }
        @Override public void close() { executor.shutdownNow(); }
    }
    public static final class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context,AnnotatedTypeMetadata metadata) {
            String value=context.getEnvironment().getProperty("exports.jobs.enabled");
            if(value!=null && !value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("El indicador de exportaciones requiere true o false exactos.");
            return "true".equals(value);
        }
    }
}
