package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class BrowserCloudinaryStorageTest {
    @TempDir Path directory;
    private final byte[] bytes={1,2,3,4};
    private final FakeStorage provider=new FakeStorage();
    private final Map<UUID,BrowserCloudinaryStorage.Expected> owned=new LinkedHashMap<>();
    private Path journal;

    @Test void localDefaultDoesNotRequireOrOpenCredentialsAndMalformedOptInFailsClosed() {
        assertThat(BrowserCloudinaryStorage.enabled(Map.of())).isFalse();
        assertThat(BrowserCloudinaryStorage.enabled(Map.of(BrowserCloudinaryStorage.CREDENTIALS,"/does/not/exist"))).isFalse();
        for(String value:List.of("", "true", "synthetic-only ", "SYNTHETIC-ONLY")) {
            assertThatThrownBy(()->BrowserCloudinaryStorage.enabled(Map.of(BrowserCloudinaryStorage.OPT_IN,value,
                    BrowserCloudinaryStorage.CREDENTIALS,"/does/not/exist"))).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        assertThatThrownBy(()->BrowserCloudinaryStorage.enabled(Map.of(BrowserCloudinaryStorage.OPT_IN,"synthetic-only")))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(BrowserCloudinaryStorage.enabled(Map.of(BrowserCloudinaryStorage.OPT_IN,"synthetic-only",
                BrowserCloudinaryStorage.CREDENTIALS,"/not-opened-during-opt-in-check"))).isTrue();
    }

    @Test void onlyThreeCredentialValuesAreSelectedWithoutImportingOtherConfiguration() throws Exception {
        Path file=directory.resolve("synthetic.properties");
        Files.writeString(file,"""
                spring.datasource.url=jdbc:postgresql://unrelated/never-import
                MAIL_PASSWORD=not-for-this-context
                CLOUD_NAME=synthetic-cloud
                API_KEY=synthetic-key
                API_SECRET=synthetic-secret
                photos.private.cloudinary.cloud-name=${CLOUD_NAME}
                photos.private.cloudinary.api-key=${API_KEY}
                photos.private.cloudinary.api-secret=${API_SECRET}
                photos.private.enabled=false
                """);
        assertThat(BrowserCloudinaryStorage.settings(file)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "photos.private.enabled","true","photos.private.cloudinary.cloud-name","synthetic-cloud",
                "photos.private.cloudinary.api-key","synthetic-key","photos.private.cloudinary.api-secret","synthetic-secret"));
    }

    @Test void duplicateOrUnresolvedCredentialIsRejectedWithoutCauseOrValue() throws Exception {
        Path file=directory.resolve("duplicate.properties");
        Files.writeString(file,"CLOUD_NAME=cloud\nAPI_KEY=key\nAPI_SECRET=private-value\nAPI_SECRET=other-value\n");
        assertThatThrownBy(()->BrowserCloudinaryStorage.settings(file)).isInstanceOf(IllegalStateException.class)
                .hasNoCause().hasMessageNotContaining("private-value").hasMessageNotContaining("other-value");
        Files.writeString(file,"CLOUD_NAME=cloud\nAPI_KEY=key\nAPI_SECRET=${UNRELATED_SECRET}\n");
        assertThatThrownBy(()->BrowserCloudinaryStorage.settings(file)).isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test void fourOwnedAssetsLoseOneInducedAckRecoverReadVerifyAclAndDeleteByExactIdentity() throws Exception {
        AtomicInteger probes=new AtomicInteger();
        try(var storage=storage(asset->{probes.incrementAndGet();return new BrowserCloudinaryStorage.ProbeResult(401,403);})) {
            for(int i=0;i<4;i++) {
                String key=own();
                uploadWithLostAck(storage,key);
                var asset=storage.find(key).orElseThrow();
                assertThat(storage.read(asset,bytes.length)).containsExactly(bytes);
                assertThat(storage.read(asset,bytes.length)).containsExactly(bytes);
                storage.delete(key,asset.assetId());
                storage.verifyRecord(key,bytes.length,sha(bytes));
            }
            storage.verifyComplete();
            assertThat(provider.uploads).isEqualTo(4);
            assertThat(provider.deletions).hasSize(4);
            assertThat(provider.assets).isEmpty();
            assertThat(probes).hasValue(4);
        }
        assertThat(Files.readString(journal)).contains("ack-loss-induced","browser-four-assets-passed-and-deleted","cleanup-complete")
                .doesNotContain("https://", "secret", "password");
    }

    @Test void unknownDatabaseRowAndNoncanonicalKeyCannotReachProvider() throws Exception {
        try(var storage=storage()) {
            assertUnavailable(()->storage.find("ordenfix-private/"+UUID.randomUUID()));
            assertUnavailable(()->storage.find("ordenfix-private/../foreign"));
            String key=own();
            assertUnavailable(()->storage.find(key.toUpperCase(Locale.ROOT)));
            assertThat(provider.finds).isZero();
            assertThat(provider.uploads).isZero();
        }
    }

    @Test void fifthOwnedKeyIsRejectedBeforeAnyProviderRequest() throws Exception {
        try(var storage=storage()) {
            for(int i=0;i<4;i++)assertThat(storage.find(own())).isEmpty();
            int before=provider.finds;
            assertUnavailable(()->storage.find(own()));
            assertThat(provider.finds).isEqualTo(before);
            assertThat(provider.uploads).isZero();
        }
    }

    @Test void preexistingCollisionIsNeverReadUploadedOrDeleted() throws Exception {
        try(var storage=storage()) {
            String key=own();var existing=provider.put(key,"preexisting");
            assertUnavailable(()->storage.find(key));
            assertUnavailable(()->storage.upload(key,"image/png",bytes));
            storage.cleanupOwned();
            assertThat(provider.assets.get(key)).isEqualTo(existing);
            assertThat(provider.uploads).isZero();assertThat(provider.reads).isZero();assertThat(provider.deletions).isEmpty();
        }
        assertThat(Files.readString(journal)).contains("preexisting-key-not-owned");
    }

    @Test void collisionAppearingBetweenFindAndUploadIsAlsoNeverClaimed() throws Exception {
        try(var storage=storage()) {
            String key=own();assertThat(storage.find(key)).isEmpty();
            var existing=provider.put(key,"concurrent-preexisting");
            assertUnavailable(()->storage.upload(key,"image/png",bytes));
            storage.cleanupOwned();
            assertThat(provider.assets.get(key)).isEqualTo(existing);
            assertThat(provider.uploads).isZero();assertThat(provider.deletions).isEmpty();
        }
    }

    @Test void incorrectBytesAndSecondUploadAreRejectedWithoutConsumingAnotherProviderUpload() throws Exception {
        try(var storage=storage()) {
            String key=own();assertThat(storage.find(key)).isEmpty();
            assertUnavailable(()->storage.upload(key,"image/png",new byte[]{8,8,8,8}));
            assertThat(provider.uploads).isZero();
            assertUnavailable(()->storage.upload(key,"image/png",bytes));
            assertUnavailable(()->storage.upload(key,"image/png",bytes));
            assertThat(provider.uploads).isEqualTo(1);
        }
    }

    @Test void wrongDeleteIdentityCannotDeleteTheOwnedAsset() throws Exception {
        try(var storage=storage()) {
            String key=own();uploadWithLostAck(storage,key);
            assertUnavailable(()->storage.delete(key,"another-asset"));
            assertThat(provider.deletions).isEmpty();assertThat(provider.assets).containsKey(key);
            storage.cleanupOwned();
            assertThat(provider.deletions).containsExactly(key+":asset1");
        }
    }

    @Test void realUncertainAckIsNotMistakenForInducedFailureAndCleanupRecoversExactBytes() throws Exception {
        provider.uncertainAck=true;
        try(var storage=storage()) {
            String key=own();uploadWithLostAck(storage,key);
            assertUnavailable(()->storage.find(key));
            assertUnavailable(()->storage.upload(key,"image/png",bytes));
            storage.cleanupOwned();
            assertThat(provider.uploads).isEqualTo(1);assertThat(provider.assets).isEmpty();
            assertThat(provider.deletions).containsExactly(key+":asset1");
        }
        assertThat(Files.readString(journal)).contains("upload-failed-cleanup-required","uncertain-upload-identity-recovered","cleanup-confirmed")
                .doesNotContain("ack-loss-induced","provider-secret", "https://");
    }

    @Test void uncertainUploadCurrentlyAbsentLeavesDurablePendingEvidence() throws Exception {
        provider.uncertainWithoutAsset=true;
        try(var storage=storage()) {
            uploadWithLostAck(storage,own());
            assertThatThrownBy(storage::cleanupOwned).isInstanceOf(AssertionError.class).hasMessageContaining("cleanup requires review");
            assertThat(provider.uploads).isEqualTo(1);assertThat(provider.deletions).isEmpty();
        }
        assertThat(Files.readString(journal)).contains("uncertain-upload-key-currently-absent-cleanup-pending").doesNotContain("cleanup-complete");
    }

    @Test void uncertainUploadWithMismatchingBytesCannotBeDeletedAsOwned() throws Exception {
        provider.uncertainAck=true;provider.wrongRead=true;
        try(var storage=storage()) {
            String key=own();uploadWithLostAck(storage,key);
            assertThatThrownBy(storage::cleanupOwned).isInstanceOf(AssertionError.class);
            assertThat(provider.assets).containsKey(key);assertThat(provider.deletions).isEmpty();
        }
        assertThat(Files.readString(journal)).contains("cleanup-pending");
    }

    @ParameterizedTest @ValueSource(ints={200,302,404,500})
    void anonymousResultOtherThan401Or403DoesNotAccreditPrivacy(int status) throws Exception {
        try(var storage=storage(asset->new BrowserCloudinaryStorage.ProbeResult(status,403))) {
            String key=own();uploadWithLostAck(storage,key);
            var asset=storage.find(key).orElseThrow();
            assertUnavailable(()->storage.read(asset,bytes.length));
            assertThatThrownBy(()->storage.verifyRecord(key,bytes.length,sha(bytes))).isInstanceOf(AssertionError.class);
            storage.cleanupOwned();
            assertThat(provider.assets).isEmpty();
        }
        assertThat(Files.readString(journal)).doesNotContain("anonymous-denied");
    }

    private BrowserCloudinaryStorage storage() throws Exception { return storage(asset->new BrowserCloudinaryStorage.ProbeResult(401,403)); }
    private BrowserCloudinaryStorage storage(BrowserCloudinaryStorage.AnonymousProbe probe) throws Exception {
        journal=Files.createTempFile(directory,"journal-",".log");
        return new BrowserCloudinaryStorage(provider,id->owned.get(id),probe,journal,()->{});
    }
    private String own() throws Exception {
        UUID id=UUID.randomUUID();owned.put(id,new BrowserCloudinaryStorage.Expected(bytes.length,sha(bytes),"image/png"));
        return "ordenfix-private/"+id;
    }
    private void uploadWithLostAck(BrowserCloudinaryStorage storage,String key) {
        assertThat(storage.find(key)).isEmpty();assertUnavailable(()->storage.upload(key,"image/png",bytes));
    }
    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(PrivatePhotoStorageException.class).hasNoCause()
                .hasMessage("No se pudo completar la operación de almacenamiento privado.");
    }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private final class FakeStorage implements PrivatePhotoStorage {
        final Map<String,StoredAsset> assets=new LinkedHashMap<>();final List<String> deletions=new ArrayList<>();
        int uploads,finds,reads;boolean uncertainAck,uncertainWithoutAsset,wrongRead;
        StoredAsset put(String key,String id) { var asset=new StoredAsset(id,key,"image/png",bytes.length,"1");assets.put(key,asset);return asset; }
        @Override public StoredAsset upload(String key,String mime,byte[] content) {
            uploads++;
            if(uncertainWithoutAsset)throw new IllegalStateException("https://provider-secret.invalid/private");
            var asset=put(key,"asset"+uploads);
            if(uncertainAck)throw new IllegalStateException("https://provider-secret.invalid/private");
            return asset;
        }
        @Override public Optional<StoredAsset> find(String key) { finds++;return Optional.ofNullable(assets.get(key)); }
        @Override public byte[] read(StoredAsset expected,int maxBytes) { reads++;return wrongRead?new byte[]{9,9,9,9}:bytes.clone(); }
        @Override public void delete(String key,String assetId) {
            assertThat(assets.get(key).assetId()).isEqualTo(assetId);deletions.add(key+":"+assetId);assets.remove(key);
        }
    }
}
