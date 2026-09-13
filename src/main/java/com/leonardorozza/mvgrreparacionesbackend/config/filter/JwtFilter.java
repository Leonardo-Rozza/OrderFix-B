package com.leonardorozza.mvgrreparacionesbackend.config.filter;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPrivateRequirementsAuthenticationEntryPoint;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
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
    private final LegalPublicRequirementsRequestMatcher legalPublicRequirementsRequestMatcher;

    @Autowired
    public JwtFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService,
                     LegalPublicDocumentRequestMatcher legalPublicDocumentRequestMatcher,
                     LegalPublicRequirementsRequestMatcher legalPublicRequirementsRequestMatcher) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.legalPublicDocumentRequestMatcher = legalPublicDocumentRequestMatcher;
        this.legalPublicRequirementsRequestMatcher = legalPublicRequirementsRequestMatcher;
    }

    public JwtFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService,
                     LegalPublicDocumentRequestMatcher legalPublicDocumentRequestMatcher) {
        this(jwtUtils, userDetailsService, legalPublicDocumentRequestMatcher,
                new LegalPublicRequirementsRequestMatcher(false));
    }

    public JwtFilter(JwtUtils jwtUtils, UserDetailsServiceImpl userDetailsService) {
        this(jwtUtils, userDetailsService, new LegalPublicDocumentRequestMatcher(false));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // No filtramos login, el webhook de MercadoPago ni el health (entran sin JWT).
        return legalPublicDocumentRequestMatcher.matches(request)
                || legalPublicRequirementsRequestMatcher.matches(request)
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
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal
                    && principal.isWorkshopRestricted() && !restrictedAccessAllowed(request)) {
                response.setStatus(423);
                response.setHeader("Cache-Control", "private, no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":423,\"code\":\"CUENTA_EN_CIERRE\",\"message\":\"El taller está en cierre. Esta operación no está disponible en la cuenta restringida.\"}");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            // Evita fugas de tenant entre requests que reutilizan el hilo.
            TenantContext.clear();
        }
    }

    /** Exact method/path admission. Sensitive services independently enforce the same lifecycle state. */
    private static boolean restrictedAccessAllowed(HttpServletRequest request) {
        // The history controller owns query validation (pagination/context, unknown or repeated keys).
        // Admit only its existing exact GET route, so that contract also works during restriction.
        if (LegalPrivateRequirementsAuthenticationEntryPoint.isHistoryGet(request)) return true;
        String uri = request.getRequestURI(), context = request.getContextPath();
        if (uri == null || context == null || (!context.isEmpty()
                && (!context.startsWith("/") || context.endsWith("/") || !uri.startsWith(context + "/")))) return false;
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) return false;
        String path = uri.substring(context.length());
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        return switch (request.getMethod()) {
            case "GET" -> path.equals("/api/perfil") || path.equals("/api/cuenta/cierre")
                    || path.equals("/api/exportaciones/actual") || path.matches("/api/exportaciones/" + id);
            case "POST" -> path.equals("/api/cuenta/reauthenticaciones")
                    || path.equals("/api/cuenta/cierre/reauthenticaciones")
                    || path.equals("/api/cuenta/cierre/operaciones")
                    || path.matches("/api/exportaciones/" + id + "/archivo");
            default -> false;
        };
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
