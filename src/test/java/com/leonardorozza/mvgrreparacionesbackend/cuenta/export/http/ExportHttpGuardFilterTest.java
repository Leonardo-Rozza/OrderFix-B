package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportWorkPermit;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ExportHttpGuardFilterTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void anonymousAndEmployeeRequestsCannotReadProofsBodiesOrInvokeConsumers() throws Exception {
        var filter=new ExportHttpGuardFilter(new ExportWorkPermit());
        AtomicInteger calls=new AtomicInteger();
        for(boolean employee:new boolean[]{false,true}) {
            if(employee) authenticate(2,UserRole.USER); else SecurityContextHolder.clearContext();
            var request=new MockHttpServletRequest("POST",ExportHttpRequests.REAUTH) {
                @Override public ServletInputStream getInputStream(){throw new AssertionError("body must not be opened");}
                @Override public String getHeader(String name) {if(name.equals("X-Reauth-Token"))throw new AssertionError("proof must not be read");return super.getHeader(name);}
            };
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(req,res)->calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(employee?403:401);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("ETag")).isNull();
        }
        assertThat(calls.get()).isZero();
    }
    @Test void theHeavyPermitRemainsHeldDuringTheActualSynchronousResponseWrite() throws Exception {
        var work=new ExportWorkPermit();var filter=new ExportHttpGuardFilter(work);
        authenticate(1,UserRole.ADMIN);
        var response=new MockHttpServletResponse();
        filter.doFilter(request("POST",ExportHttpRequests.EXCEL),response,(req,res)->{
            assertThat(work.tryAcquire()).isNull();
            res.getOutputStream().write(new byte[]{1,2,3});res.flushBuffer();
            assertThat(work.tryAcquire()).isNull();
        });
        assertThat(response.getContentAsByteArray()).containsExactly(1,2,3);
        try(var available=work.tryAcquire()){assertThat(available).isNotNull();}
    }
    @Test void anInterruptedResponseStillReleasesTheHeavyPermit() throws Exception {
        var work=new ExportWorkPermit();var filter=new ExportHttpGuardFilter(work);authenticate(1,UserRole.ADMIN);
        assertThatThrownBy(()->filter.doFilter(request("POST",ExportHttpRequests.EXCEL),new MockHttpServletResponse(),(req,res)->{
            assertThat(work.tryAcquire()).isNull();throw new IOException("synthetic disconnected client");
        })).isInstanceOf(IOException.class);
        try(var available=work.tryAcquire()){assertThat(available).isNotNull();}
    }
    @Test void aWorkerHoldingTheSamePermitRejectsDownloadsWithoutCallingTheController() throws Exception {
        var work=new ExportWorkPermit();var filter=new ExportHttpGuardFilter(work);authenticate(1,UserRole.ADMIN);
        AtomicInteger calls=new AtomicInteger();var response=new MockHttpServletResponse();
        try(var worker=work.tryAcquire()) {
            assertThat(worker).isNotNull();
            for(int attempt=0;attempt<4;attempt++) {
                response=new MockHttpServletResponse();
                filter.doFilter(request("POST",ExportHttpRequests.EXCEL),response,(req,res)->calls.incrementAndGet());
            }
            assertThat(response.getStatus()).isEqualTo(429);assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getContentAsString()).contains("EXPORTACION_LIMITE");
        }
        assertThat(calls.get()).isZero();
        filter.doFilter(request("POST",ExportHttpRequests.EXCEL),new MockHttpServletResponse(),(req,res)->calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void zipAndExcelConsumeTheSamePerActorDownloadQuotaAcrossSessions() throws Exception {
        var filter=new ExportHttpGuardFilter(new ExportWorkPermit());authenticate(1,UserRole.ADMIN);AtomicInteger calls=new AtomicInteger();
        String zip="/api/exportaciones/8ca53cde-7d69-48d0-8b7f-804bd395e269/archivo";
        for(String route:java.util.List.of(zip,ExportHttpRequests.EXCEL,zip)) {
            var request=request("POST",route);request.removeHeader("Authorization");request.addHeader("Authorization","Bearer other-session-"+calls.get());
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->calls.incrementAndGet());assertThat(response.getStatus()).isEqualTo(200);
        }
        var limited=new MockHttpServletResponse();filter.doFilter(request("POST",ExportHttpRequests.EXCEL),limited,(req,res)->calls.incrementAndGet());
        assertThat(limited.getStatus()).isEqualTo(429);assertThat(calls.get()).isEqualTo(3);
    }
    @Test void onlyTwoPasswordVerificationsCanRunAtOnceAndFailureReturnsTheirSlots() throws Exception {
        var filter=new ExportHttpGuardFilter(new ExportWorkPermit());var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> running=()-> {
                authenticate(Thread.currentThread().threadId(),UserRole.ADMIN);
                try {
                    var response=new MockHttpServletResponse();
                    filter.doFilter(request("POST",ExportHttpRequests.REAUTH),response,(req,res)->{
                        entered.countDown();
                        try {if(!release.await(5,TimeUnit.SECONDS))throw new ServletException("barrier expired");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ServletException("barrier interrupted");}
                    });
                    return response.getStatus();
                } finally {SecurityContextHolder.clearContext();}
            };
            var first=executor.submit(running);var second=executor.submit(running);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();authenticate(999,UserRole.ADMIN);
                var response=new MockHttpServletResponse();AtomicInteger calls=new AtomicInteger();
                filter.doFilter(request("POST",ExportHttpRequests.REAUTH),response,(req,res)->calls.incrementAndGet());
                assertThat(response.getStatus()).isEqualTo(429);assertThat(calls.get()).isZero();
            } finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(200);assertThat(second.get(5,TimeUnit.SECONDS)).isEqualTo(200);
        }
        authenticate(1000,UserRole.ADMIN);
        assertThatThrownBy(()->filter.doFilter(request("POST",ExportHttpRequests.REAUTH),new MockHttpServletResponse(),(req,res)->{throw new ServletException("fixture");})).isInstanceOf(ServletException.class);
        var response=new MockHttpServletResponse();filter.doFilter(request("POST",ExportHttpRequests.REAUTH),response,(req,res)->{});assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void nonExportRoutesAreUnaffectedAndHeadCannotStartAReport() throws Exception {
        var filter=new ExportHttpGuardFilter(new ExportWorkPermit());AtomicInteger calls=new AtomicInteger();var other=new MockHttpServletResponse();
        filter.doFilter(request("GET","/api/clientes"),other,(req,res)->calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);assertThat(other.getHeader("Cache-Control")).isNull();
        authenticate(1,UserRole.ADMIN);var response=new MockHttpServletResponse();
        filter.doFilter(request("HEAD",ExportHttpRequests.EXCEL),response,(req,res)->calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(405);assertThat(calls.get()).isEqualTo(1);
    }
    private static MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);request.addHeader("Authorization","Bearer synthetic.jwt.token");return request;
    }
    private static void authenticate(long id,UserRole role) {
        var taller=new Taller();taller.setId(7L);taller.setActivo(true);
        var user=User.builder().id(id).taller(taller).role(role).username("Export fixture").email("export@fixture.test")
                .password("fixture-password").active(true).emailVerificado(true).tokenVersion(1L).build();
        var actor=new AuthenticatedUserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,actor.getAuthorities()));
    }
}
