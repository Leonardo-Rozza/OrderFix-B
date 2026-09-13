package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leonardorozza.mvgrreparacionesbackend.exceptions.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Authenticated ADMIN admission before reading JSON/proofs or invoking password/SQL work. */
public final class WorkshopClosureHttpGuardFilter extends OncePerRequestFilter {
    private final Semaphore work=new Semaphore(4), passwords=new Semaphore(2);
    private final WorkshopClosureHttpRateLimit limits;
    private final LongSupplier nanos;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public WorkshopClosureHttpGuardFilter() { this(System::nanoTime,2048); }
    WorkshopClosureHttpGuardFilter(LongSupplier nanos,int actors) { this.nanos=nanos; limits=new WorkshopClosureHttpRateLimit(actors); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !WorkshopClosureHttpRequests.matches(request); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        noStore(response); boolean admitted=false,password=false;
        try {
            var actor=WorkshopClosureHttpRequests.actor();
            WorkshopClosureHttpRequests.accessToken(request);
            var operation=WorkshopClosureHttpRequests.operation(request); WorkshopClosureHttpRequests.require(request,operation);
            if(!work.tryAcquire()) throw WorkshopClosureHttpException.limited(1); admitted=true;
            if(operation==WorkshopClosureHttpRequests.Operation.ISSUE) {
                if(!passwords.tryAcquire()) throw WorkshopClosureHttpException.limited(1); password=true;
            }
            limits.acquire(actor.getUserId(),operation,nanos.getAsLong());
            chain.doFilter(request,response);
        } catch(WorkshopClosureHttpException failure) {
            if(response.isCommitted()) throw new ServletException("No se pudo completar la respuesta de cierre.");
            noStore(response); response.setStatus(failure.status.value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            if(failure.retryAfter>0) response.setHeader(HttpHeaders.RETRY_AFTER,Long.toString(failure.retryAfter));
            json.writeValue(response.getOutputStream(),new ApiError(LocalDateTime.now(),failure.status.value(),failure.status.getReasonPhrase(),
                    failure.getMessage(),request.getRequestURI(),failure.code,null));
        } finally { if(password) passwords.release(); if(admitted) work.release(); }
    }
    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"private, no-store"); response.setHeader(HttpHeaders.ETAG,null);
        response.setHeader("X-Content-Type-Options","nosniff");
    }
}
