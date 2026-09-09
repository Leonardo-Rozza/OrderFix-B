package com.leonardorozza.mvgrreparacionesbackend.photos;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalRequestMetadata;
import java.util.List;
import java.util.UUID;
import static com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoDtos.*;

public interface PrivatePhotoService extends AutoCloseable {
    Requirements requirements(AuthenticatedUserPrincipal principal, long repair);
    Result<Intention> create(AuthenticatedUserPrincipal principal, long repair, String key, Create input, LegalRequestMetadata metadata);
    Intention intention(AuthenticatedUserPrincipal principal, long repair, UUID id);
    Intention upload(AuthenticatedUserPrincipal principal, long repair, UUID id, String mime, byte[] content);
    Result<Photo> finish(AuthenticatedUserPrincipal principal, long repair, UUID id);
    List<Photo> photos(AuthenticatedUserPrincipal principal, long repair);
    Content content(AuthenticatedUserPrincipal principal, long repair, UUID id);
    void delete(AuthenticatedUserPrincipal principal, long repair, UUID id);
    int cleanup();
    @Override void close();
}
