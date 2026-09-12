package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportWorkPermit;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Runs after JWT authentication, only for export routes. Synchronous response writes retain their heavy-work permit. */
public final class ExportHttpGuardFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(ExportHttpGuardFilter.class);
    private final ExportWorkPermit work;
    private final LongSupplier nanos;
    private final ExportHttpRateLimit limits;
    private final Semaphore passwordWork=new Semaphore(2);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public ExportHttpGuardFilter(ExportWorkPermit work) { this(work,System::nanoTime,2048); }
    ExportHttpGuardFilter(ExportWorkPermit work,LongSupplier nanos,int maxActors) {
        this.work=java.util.Objects.requireNonNull(work); this.nanos=nanos; this.limits=new ExportHttpRateLimit(maxActors);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !ExportHttpRequests.matches(request); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        noStore(response);
        ExportWorkPermit.Lease lease=null; boolean passwordPermit=false;
        try {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if(authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal actor)
                    || !actor.isEnabled() || actor.getUserId()==null || actor.getTallerId()==null) throw ExportHttpException.unauthorized();
            if(actor.getAuthorities().stream().noneMatch(authority->authority.getAuthority().equals("ROLE_ADMIN"))) throw ExportHttpException.forbidden();
            ExportHttpRequests.accessToken(request);
            var operation=ExportHttpRequests.operation(request);
            ExportHttpRequests.require(request,operation);
            if(operation==ExportHttpRequests.Operation.ISSUE) {
                if(!passwordWork.tryAcquire()) throw ExportHttpException.limited(1);
                passwordPermit=true;
            }
            if(operation==ExportHttpRequests.Operation.DOWNLOAD) {
                lease=work.tryAcquire(); if(lease==null) throw ExportHttpException.limited(1);
            }
            // A busy resource does not spend a quota or a proof; rejected quotas immediately release permits.
            limits.acquire(actor.getUserId(),operation,nanos.getAsLong());
            chain.doFilter(request,response);
            if(operation==ExportHttpRequests.Operation.DOWNLOAD && response.getStatus()==200) {
                String path=ExportHttpRequests.path(request);
                String format=path.equals(ExportHttpRequests.EXCEL)?"XLSX":"ZIP";
                String id=format.equals("ZIP")?path.substring(ExportHttpRequests.EXPORTS.length()+1,path.length()-8):"none";
                // Successful synchronous server write, never a claim that the browser received or saved it.
                LOG.info("exportacion_respuesta_escrita actor={} taller={} formato={} exportacion={}",actor.getUserId(),actor.getTallerId(),format,id);
            }
        } catch(ExportHttpException failure) {
            if(response.isCommitted()) throw new ServletException("No se pudo completar la respuesta de exportación.");
            error(failure,request,response);
        } finally {
            if(lease!=null) lease.close();
            if(passwordPermit) passwordWork.release();
        }
    }
    private void error(ExportHttpException failure,HttpServletRequest request,HttpServletResponse response)throws IOException {
        noStore(response); response.setStatus(failure.status.value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if(failure.retryAfter>0) response.setHeader(HttpHeaders.RETRY_AFTER,Long.toString(failure.retryAfter));
        json.writeValue(response.getOutputStream(),new ApiError(LocalDateTime.now(),failure.status.value(),failure.status.getReasonPhrase(),
                failure.getMessage(),request.getRequestURI(),failure.code,null));
    }
    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"private, no-store"); response.setHeader(HttpHeaders.ETAG,null);
        response.setHeader("X-Content-Type-Options","nosniff");
    }
}
