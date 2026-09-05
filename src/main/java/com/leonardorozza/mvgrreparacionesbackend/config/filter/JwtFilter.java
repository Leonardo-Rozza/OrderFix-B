package com.leonardorozza.mvgrreparacionesbackend.config.filter;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserDetailsServiceImpl;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;
    private final LegalPublicDocumentRequestMatcher legalPublicDocumentRequestMatcher;

    @Autowired
    public JwtFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService,
                     LegalPublicDocumentRequestMatcher legalPublicDocumentRequestMatcher) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.legalPublicDocumentRequestMatcher = legalPublicDocumentRequestMatcher;
    }

    public JwtFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService) {
        this(jwtUtils, userDetailsService, new LegalPublicDocumentRequestMatcher(false));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // No filtramos login, el webhook de MercadoPago ni el health (entran sin JWT).
        return legalPublicDocumentRequestMatcher.matches(request)
                || path.startsWith("/api/auth/")
                || path.equals("/api/pagos/webhook")
                || path.startsWith("/api/seguimiento/")
                || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            authenticateRequest(request);
            filterChain.doFilter(request, response);
        } finally {
            // Evita fugas de tenant entre requests que reutilizan el hilo.
            TenantContext.clear();
        }
    }

    private void authenticateRequest(HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return;
        }

        try {
            DecodedJWT decoded = jwtUtils.verifyToken(authHeader.substring(7));
            AuthenticatedUserPrincipal principal =
                    userDetailsService.loadUserByUsername(decoded.getSubject());

            if (!jwtUtils.validateToken(decoded, principal)) {
                log.debug("JWT rechazado por no coincidir con el estado actual de la cuenta.");
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities()
                    );
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // El tenant se toma del usuario persistido, nunca del claim controlado por el token.
            TenantContext.setTallerId(principal.getTallerId());
        } catch (JWTVerificationException | UsernameNotFoundException | IllegalArgumentException ex) {
            // Fallo esperado de autenticación: no se exponen ni registran detalles criptográficos.
            log.debug("JWT rechazado.");
        }
    }
}
