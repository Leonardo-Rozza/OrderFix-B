package com.leonardorozza.mvgrreparacionesbackend.photos;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import static com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoDtos.*;

@RestController
@RequestMapping("/api/reparaciones/{repair}")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class PrivatePhotoController {
    private final ObjectProvider<PrivatePhotoService> services;
    public PrivatePhotoController(ObjectProvider<PrivatePhotoService> services) { this.services=services; }
    private PrivatePhotoService service() {
        var result=services.getIfAvailable(); if(result==null) throw PrivatePhotoException.unavailable(); return result;
    }
    @GetMapping("/requisitos-fotos")
    public ResponseEntity<Requirements> requirements(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair) {
        return response(HttpStatus.OK,service().requirements(actor,repair));
    }
    @PostMapping("/cargas-foto")
    public ResponseEntity<Intention> create(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody Create input,HttpServletRequest request) {
        LegalRequestMetadata metadata;
        try { metadata=LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral(request.getRemoteAddr()),request.getHeader("User-Agent")); }
        catch(RuntimeException failure) { throw PrivatePhotoException.unavailable(); }
        Result<Intention> result=service().create(actor,repair,key,input,metadata);
        return response(result.reused()?HttpStatus.OK:HttpStatus.CREATED,result.value());
    }
    @GetMapping("/cargas-foto/{id}")
    public ResponseEntity<Intention> intention(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,@PathVariable UUID id) {
        return response(HttpStatus.OK,service().intention(actor,repair,id));
    }
    @PutMapping("/cargas-foto/{id}/contenido")
    public ResponseEntity<Intention> upload(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,
            @PathVariable UUID id,HttpServletRequest request) throws IOException {
        var service=service();
        // Authenticate persisted actor/tenant before consuming a potentially large body.
        service.intention(actor,repair,id);
        if(request.getContentLengthLong()>PrivatePhotoImageValidator.MAX_BYTES) throw PrivatePhotoException.invalid();
        byte[] bytes=request.getInputStream().readNBytes(PrivatePhotoImageValidator.MAX_BYTES+1);
        return response(HttpStatus.OK,service.upload(actor,repair,id,request.getContentType(),bytes));
    }
    @PostMapping("/cargas-foto/{id}/finalizaciones")
    public ResponseEntity<Photo> finish(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,
            @PathVariable UUID id,HttpServletRequest request) throws IOException {
        if(request.getInputStream().read()!=-1) throw PrivatePhotoException.invalid();
        var result=service().finish(actor,repair,id);
        return response(result.reused()?HttpStatus.OK:HttpStatus.CREATED,result.value());
    }
    @GetMapping("/fotos")
    public ResponseEntity<java.util.List<Photo>> photos(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair) {
        return response(HttpStatus.OK,service().photos(actor,repair));
    }
    @GetMapping("/fotos/{id}/contenido")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,@PathVariable UUID id) {
        var content=service().content(actor,repair,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","inline; filename=photo")
                .contentType(MediaType.parseMediaType(content.mimeType())).body(content.bytes());
    }
    @DeleteMapping("/fotos/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,@PathVariable long repair,@PathVariable UUID id) {
        service().delete(actor,repair,id); return response(HttpStatus.NO_CONTENT,null);
    }
    private static <T>ResponseEntity<T> response(HttpStatus status,T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
    @ExceptionHandler(PrivatePhotoException.class)
    public ResponseEntity<Map<String,Object>> failure(PrivatePhotoException failure) {
        var builder=ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON);
        if(failure.code().equals("OPERACION_EN_PROGRESO")) builder.header("Retry-After","1");
        return builder.body(Map.of("status",failure.status().value(),"error",failure.status().getReasonPhrase(),
                "code",failure.code(),"message",failure.getMessage(),"details",failure.details()));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> invalid(Exception ignored) { return failure(PrivatePhotoException.invalid()); }
}
