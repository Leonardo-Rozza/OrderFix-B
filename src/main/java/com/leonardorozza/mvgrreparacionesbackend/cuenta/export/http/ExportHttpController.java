package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportJobService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class ExportHttpController {
    private static final Logger LOG=LoggerFactory.getLogger(ExportHttpController.class);
    private final ExportReauthenticationService reauthentication;
    private final ObjectProvider<ExportJobService> services;
    public ExportHttpController(ExportReauthenticationService reauthentication,ObjectProvider<ExportJobService> services) {
        this.reauthentication=reauthentication; this.services=services;
    }
    public record ReauthenticationResponse(String reauthToken,ExportReauthenticationPurpose proposito,Instant expiresAt) {
        @Override public String toString() { return "ReauthenticationResponse[REDACTED]"; }
    }
    public record StatusResponse(UUID id,String estado,Instant expiresAt,boolean reused) { }
    public record CurrentResponse(boolean habilitada,StatusResponse exportacion) { }

    @PostMapping(path=ExportHttpRequests.REAUTH,consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReauthenticationResponse> reauthenticate(HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.ISSUE);
        String token=ExportHttpRequests.accessToken(request);
        var input=ExportHttpRequests.reauthentication(request);
        if(input.proposito()==ExportReauthenticationPurpose.EXPORTAR) service();
        var grant=reauthentication.issue(token,input.passwordActual(),input.proposito());
        return json(HttpStatus.OK,new ReauthenticationResponse(grant.token(),input.proposito(),grant.expiresAt()));
    }
    @PostMapping(path=ExportHttpRequests.EXPORTS,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatusResponse> request(HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.REQUEST); ExportHttpRequests.emptyBody(request);
        var result=service().request(ExportHttpRequests.accessToken(request),ExportHttpRequests.proof(request),ExportHttpRequests.requestKey(request));
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .location(java.net.URI.create(ExportHttpRequests.EXPORTS+"/"+result.id())).body(status(result));
    }
    @GetMapping(path=ExportHttpRequests.EXPORTS+"/actual",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrentResponse> current(HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.READ); ExportHttpRequests.emptyBody(request);
        String token=ExportHttpRequests.accessToken(request); var service=services.getIfAvailable();
        if(service==null) return json(HttpStatus.OK,new CurrentResponse(false,null));
        return json(HttpStatus.OK,new CurrentResponse(true,service.latest(token).map(ExportHttpController::status).orElse(null)));
    }
    @GetMapping(path=ExportHttpRequests.EXPORTS+"/{id}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatusResponse> status(@PathVariable String id,HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.READ); ExportHttpRequests.emptyBody(request);
        return json(HttpStatus.OK,status(service().status(ExportHttpRequests.accessToken(request),ExportHttpRequests.canonicalUuid(id))));
    }
    @PostMapping(path=ExportHttpRequests.EXPORTS+"/{id}/archivo")
    public ResponseEntity<byte[]> archive(@PathVariable String id,@AuthenticationPrincipal AuthenticatedUserPrincipal actor,HttpServletRequest request) {
        ExportHttpRequests.require(request,ExportHttpRequests.Operation.DOWNLOAD); ExportHttpRequests.emptyBody(request);
        UUID jobId=ExportHttpRequests.canonicalUuid(id);
        byte[] bytes=service().authorizedArchive(ExportHttpRequests.accessToken(request),ExportHttpRequests.proof(request),jobId);
        LOG.info("exportacion_autorizada actor={} taller={} formato=ZIP exportacion={}",actor.getUserId(),actor.getTallerId(),jobId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"ordenfix-exportacion.zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).contentLength(bytes.length).body(bytes);
    }
    private ExportJobService service() { var value=services.getIfAvailable(); if(value==null) throw ExportHttpException.unavailable(); return value; }
    private static StatusResponse status(ExportJobService.Status value) { return new StatusResponse(value.id(),value.state(),value.expiresAt(),value.reused()); }
    private static <T>ResponseEntity<T> json(HttpStatus status,T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(body);
    }
}
