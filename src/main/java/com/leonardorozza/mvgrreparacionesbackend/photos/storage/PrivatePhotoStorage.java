package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import java.util.Optional;

/** Server-only storage: callers never supply a provider URL or upload credentials. */
public interface PrivatePhotoStorage {
    StoredAsset upload(String objectKey, String mimeType, byte[] content);
    Optional<StoredAsset> find(String objectKey);
    byte[] read(StoredAsset expected, int maxBytes);
    void delete(String objectKey, String assetId);

    record StoredAsset(String assetId, String objectKey, String mimeType, long bytes, String version) { }
}
