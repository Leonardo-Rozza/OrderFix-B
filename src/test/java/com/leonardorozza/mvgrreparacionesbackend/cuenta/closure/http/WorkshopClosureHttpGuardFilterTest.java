package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class WorkshopClosureHttpGuardFilterTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void anonymousEmployeeAndUnverifiedAdminCannotReadBodiesOrInvokeServices() throws Exception {
        var filter=new WorkshopClosureHttpGuardFilter();AtomicInteger calls=new AtomicInteger();
        for(int actor=0;actor<3;actor++) {
            if(actor==0) SecurityContextHolder.clearContext(); else authenticate(actor,actor==1?UserRole.USER:UserRole.ADMIN,actor==1);
            var request=new MockHttpServletRequest("POST",WorkshopClosureHttpRequests.REAUTH){
                @Override public ServletInputStream getInputStream(){throw new AssertionError("No unauthorized body read");}
            };
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(actor==0?401:403);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
            assertThat(response.getHeader("ETag")).isNull();assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        assertThat(calls.get()).isZero();
    }
    @Test void quotasFollowTheActorAcrossNewSessionsAndExpireAtTheExactWindow() throws Exception {
        var now=new java.util.concurrent.atomic.AtomicLong();var filter=new WorkshopClosureHttpGuardFilter(now::get,2);authenticate(1,UserRole.ADMIN,true);
        for(int call=0;call<5;call++) {var response=new MockHttpServletResponse();filter.doFilter(request(),response,(req,res)->{});assertThat(response.getStatus()).isEqualTo(200);}
        authenticate(1,UserRole.ADMIN,true);var limited=new MockHttpServletResponse();filter.doFilter(request(),limited,(req,res)->{throw new AssertionError("quota");});
        assertThat(limited.getStatus()).isEqualTo(429);assertThat(limited.getHeader("Retry-After")).isEqualTo("900");
        now.set(Duration.ofMinutes(15).toNanos());var allowed=new MockHttpServletResponse();filter.doFilter(request(),allowed,(req,res)->{});assertThat(allowed.getStatus()).isEqualTo(200);
    }
    @Test void aFullActorMapDoesNotEvictActiveQuotas() throws Exception {
        var filter=new WorkshopClosureHttpGuardFilter(()->0,1);authenticate(1,UserRole.ADMIN,true);filter.doFilter(request(),new MockHttpServletResponse(),(req,res)->{});
        authenticate(2,UserRole.ADMIN,true);var response=new MockHttpServletResponse();filter.doFilter(request(),response,(req,res)->{throw new AssertionError("map full");});
        assertThat(response.getStatus()).isEqualTo(429);
    }
    @Test void onlyTwoPasswordRequestsRunTogetherAndExceptionsReleaseTheirPermits() throws Exception {
        var filter=new WorkshopClosureHttpGuardFilter();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> call=()-> {authenticate(Thread.currentThread().threadId(),UserRole.ADMIN,true);
                try {var response=new MockHttpServletResponse();filter.doFilter(request(),response,(req,res)->{entered.countDown();await(release);});return response.getStatus();}
                finally {SecurityContextHolder.clearContext();}};
            var first=executor.submit(call);var second=executor.submit(call);
            try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();authenticate(99,UserRole.ADMIN,true);
                var denied=new MockHttpServletResponse();filter.doFilter(request(),denied,(req,res)->{throw new AssertionError("password permit");});assertThat(denied.getStatus()).isEqualTo(429);
            } finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(200);assertThat(second.get(5,TimeUnit.SECONDS)).isEqualTo(200);
        }
        authenticate(999,UserRole.ADMIN,true);
        assertThatThrownBy(()->filter.doFilter(request(),new MockHttpServletResponse(),(req,res)->{throw new IOException("fixture disconnect");})).isInstanceOf(IOException.class);
        var allowed=new MockHttpServletResponse();filter.doFilter(request(),allowed,(req,res)->{});assertThat(allowed.getStatus()).isEqualTo(200);
    }
    @Test void unrelatedRoutesRemainUntouched() throws Exception {
        var response=new MockHttpServletResponse();AtomicInteger calls=new AtomicInteger();new WorkshopClosureHttpGuardFilter().doFilter(
                new MockHttpServletRequest("GET","/api/clientes"),response,(req,res)->calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);assertThat(response.getHeader("Cache-Control")).isNull();
    }
    static MockHttpServletRequest request(){var request=new MockHttpServletRequest("POST",WorkshopClosureHttpRequests.REAUTH);request.setContentType("application/json");request.addHeader("Authorization","Bearer fixture.jwt.token");return request;}
    static void authenticate(long id,UserRole role,boolean verified){
        var taller=new Taller();taller.setId(7L);taller.setActivo(true);
        var user=User.builder().id(id).taller(taller).role(role).username("fixture").email("fixture@synthetic.invalid").password("fixture-hash")
                .active(true).emailVerificado(verified).tokenVersion(1L).build();
        var actor=new AuthenticatedUserPrincipal(user);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,actor.getAuthorities()));
    }
    private static void await(CountDownLatch latch)throws ServletException{try{if(!latch.await(5,TimeUnit.SECONDS))throw new ServletException("Fixture deadline");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new ServletException("Fixture interrupted");}}
}
