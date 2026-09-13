package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@Conditional(WorkshopClosureHttpConfiguration.Enabled.class)
@PreAuthorize("hasRole('ADMIN')")
public class WorkshopClosureHttpController {
    private final WorkshopClosureStatusService status;
    private final WorkshopClosureReauthenticationService reauthentication;
    private final WorkshopClosureCommandService commands;
    public WorkshopClosureHttpController(WorkshopClosureStatusService status,WorkshopClosureReauthenticationService reauthentication,WorkshopClosureCommandService commands) {
        this.status=status; this.reauthentication=reauthentication; this.commands=commands;
    }
    @GetMapping(path=WorkshopClosureHttpRequests.ROOT,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StatusResponse> status(HttpServletRequest request) {
        WorkshopClosureHttpRequests.actor(); WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.READ);
        WorkshopClosureHttpRequests.emptyBody(request);
        var value=status.read(WorkshopClosureHttpRequests.accessToken(request));
        return json(new StatusResponse(value.state(),value.workshopName(),value.observedAt(),value.reference(),value.confirmedAt(),
                value.reversibleUntil(),value.canRequest(),value.canRestore(),receipt(value.lastOperation())));
    }
    @PostMapping(path=WorkshopClosureHttpRequests.REAUTH,consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReauthenticationResponse> reauthenticate(HttpServletRequest request) {
        WorkshopClosureHttpRequests.actor(); WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.ISSUE);
        String access=WorkshopClosureHttpRequests.accessToken(request); var input=WorkshopClosureHttpRequests.reauthentication(request);
        var grant=reauthentication.issue(access,input.passwordActual(),input.purpose(),input.operationId(),input.reference());
        return json(new ReauthenticationResponse(grant.token(),grant.expiresAt()));
    }
    @PostMapping(path=WorkshopClosureHttpRequests.COMMAND,consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReceiptResponse> execute(HttpServletRequest request) {
        WorkshopClosureHttpRequests.actor(); WorkshopClosureHttpRequests.require(request,WorkshopClosureHttpRequests.Operation.COMMAND);
        String access=WorkshopClosureHttpRequests.accessToken(request),proof=WorkshopClosureHttpRequests.proof(request);
        var input=WorkshopClosureHttpRequests.command(request);
        return json(receipt(commands.execute(access,proof,input.purpose(),input.operationId(),input.reference(),input.confirmation())));
    }
    public record ReauthenticationResponse(String token,Instant expiresAt) { @Override public String toString(){return "ReauthenticationResponse[redacted]";} }
    public record ReceiptResponse(UUID operacionId,UUID referencia,WorkshopClosurePurpose proposito,String estadoResultante,
                                  Instant confirmadoEn,Instant reversibleHasta,Instant registradaEn,boolean reutilizada) {
        @Override public String toString(){return "ReceiptResponse[redacted]";}
    }
    public record StatusResponse(String estado,String tallerNombre,Instant observadoEn,UUID referencia,Instant confirmadoEn,
                                 Instant reversibleHasta,boolean puedeSolicitar,boolean puedeRestaurar,ReceiptResponse ultimaOperacion) {
        @Override public String toString(){return "StatusResponse[redacted]";}
    }
    private static ReceiptResponse receipt(WorkshopClosureCommandService.Receipt value) {
        return value==null?null:new ReceiptResponse(value.operationId(),value.reference(),value.purpose(),value.stateAtCommit(),
                value.confirmedAt(),value.reversibleUntil(),value.completedAt(),value.reused());
    }
    private static <T> ResponseEntity<T> json(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(body);
    }
}
