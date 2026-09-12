package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoImageValidator;
import com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.Taller;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Supplier;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException.Code.*;

/** Reuses the private-photo read protocol, including its second revocation check after provider I/O. */
public final class ExportPhotoReader {
    private final Supplier<PrivatePhotoService> photos;
    public ExportPhotoReader(Supplier<PrivatePhotoService> photos) { this.photos=Objects.requireNonNull(photos); }
    public List<ExportArtifactCodec.PhotoFile> read(ExportSnapshot snapshot,long tokenVersion,Runnable checkpoint) {
        if(snapshot.pendingPhotos().isEmpty()) return List.of();
        if(snapshot.pendingPhotos().size()>256) throw new ExportPackageException(CAPACITY_EXCEEDED);
        PrivatePhotoService service=photos.get();
        if(service==null) throw new ExportPackageException(UNAVAILABLE);
        try {
            var metadata=snapshot.files().stream().filter(file->file.path().equals("datos/fotos_privadas.json")).findFirst().orElseThrow();
            var out=new ByteArrayOutputStream(); metadata.writeTo(out);
            Map<UUID,Long> repairs=new HashMap<>();
            for(var row:new ObjectMapper().readTree(out.toByteArray())) {
                if("PENDIENTE_C".equals(row.path("archivo_estado").asText())) {
                    UUID id=UUID.fromString(row.path("id").asText());
                    long repair=Long.parseLong(row.path("reparacion_id").asText());
                    if(repair<=0 || repairs.put(id,repair)!=null) throw new ExportPackageException(INVALID_EVIDENCE);
                }
            }
            var workshop=new Taller(); workshop.setId(snapshot.tallerId()); workshop.setActivo(true);
            // No password is loaded or used. The private-photo service rechecks this server-side identity in PostgreSQL.
            var user=User.builder().id(snapshot.actorId()).taller(workshop).role(UserRole.ADMIN)
                    .email("export-worker@internal.invalid").password("internal-non-login-placeholder")
                    .active(true).emailVerificado(true).tokenVersion(tokenVersion).build();
            var principal=new AuthenticatedUserPrincipal(user);
            var result=new ArrayList<ExportArtifactCodec.PhotoFile>(); long total=0;
            for(var expected:snapshot.pendingPhotos()) {
                checkpoint.run();
                Long repair=repairs.remove(expected.id());
                if(repair==null) throw new ExportPackageException(INVALID_EVIDENCE);
                total+=expected.bytes();
                if(total>64L*1024*1024) throw new ExportPackageException(CAPACITY_EXCEEDED);
                var content=service.content(principal,repair,expected.id());
                String mime=expected.path().endsWith(".png")?"image/png":"image/jpeg";
                if(!mime.equals(content.mimeType())) throw new ExportPackageException(INVALID_EVIDENCE);
                PrivatePhotoImageValidator.validate(mime,content.bytes(),expected.bytes(),expected.sha256());
                result.add(new ExportArtifactCodec.PhotoFile(expected.id(),content.bytes()));
                checkpoint.run();
            }
            if(!repairs.isEmpty()) throw new ExportPackageException(INVALID_EVIDENCE);
            return List.copyOf(result);
        } catch(ExportPackageException failure) { throw failure; }
        catch(RuntimeException failure) { throw new ExportPackageException(UNAVAILABLE); }
        catch(java.io.IOException failure) { throw new ExportPackageException(INVALID_EVIDENCE); }
    }
}
