package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;

class LegalPhotoFingerprintTest {
    private static final String KEY="12345678-1234-4123-8123-123456789abc";
    private static final String REV="sha256:"+"a".repeat(64);
    private static final byte[] SECRET=new byte[32];
    private static final LegalActorSnapshot ACTOR=new LegalActorSnapshot(7,9,UserRole.ADMIN,0,true,true);
    private static LegalAcceptanceCommand command(long repair,String name,String mime,long bytes,String hash,String moment) {
        return LegalAcceptanceCommandValidator.photo(ACTOR,REV,List.of(),
                new LegalAcceptanceCommand.PhotoContext(repair,name,mime,bytes,hash,moment));
    }
    private static LegalIdempotencyFingerprint derive(LegalAcceptanceCommand value) {
        return LegalIdempotencyFingerprint.derive(value,KEY,1,SECRET);
    }
    @Test void photoProjectionHasAnIndependentOperationAndExactCanonicalManifest() throws Exception {
        var value=derive(command(17,"foto.png","image/png",128,"b".repeat(64),"INGRESO"));
        assertThat(value.operation()).isEqualTo(TipoOperacionIdempotenteLegal.ATESTACION_FOTOS);
        String route="/api/reparaciones/{reparacionId}/cargas-foto";
        String prefix="[\"ordenfix:legal-idempotency:fingerprint:v1\",\"POST\",\""+route+"\",{\"kind\":\"AUTHENTICATED\",\"userId\":\"7\"},";
        String business="{\"aceptacionesLegales\":[],\"foto\":{\"atestacionConfirmada\":true,\"bytes\":128,\"mimeType\":\"image/png\",\"momento\":\"INGRESO\",\"nombre\":\"foto.png\",\"reparacionId\":\"17\",\"sha256\":\""+"b".repeat(64)+"\"},\"requiredSetRevision\":\""+REV+"\"}]";
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET,"HmacSHA256"));
        assertThat(value.fingerprintHmac()).isEqualTo(HexFormat.of().formatHex(mac.doFinal((prefix+business).getBytes(StandardCharsets.UTF_8))));
        var historical=derive(LegalAcceptanceCommandValidator.authenticated(ACTOR,REV,List.of()));
        assertThat(value.scopeHmac()).isNotEqualTo(historical.scopeHmac());
        assertThat(value.toString()).doesNotContain(value.fingerprintHmac(),value.scopeHmac(),"foto.png");
    }
    @Test void everyManifestFieldBindsTheSameActorAndKeyWithoutCreatingAnotherScope() {
        var first=derive(command(17,"foto.png","image/png",128,"b".repeat(64),"INGRESO"));
        var changed=List.of(command(18,"foto.png","image/png",128,"b".repeat(64),"INGRESO"),
                command(17,"otra.png","image/png",128,"b".repeat(64),"INGRESO"),
                command(17,"foto.png","image/jpeg",128,"b".repeat(64),"INGRESO"),
                command(17,"foto.png","image/png",129,"b".repeat(64),"INGRESO"),
                command(17,"foto.png","image/png",128,"c".repeat(64),"INGRESO"),
                command(17,"foto.png","image/png",128,"b".repeat(64),"POST_REPARACION"));
        for(var item:changed) {
            var next=derive(item);
            assertThat(next.scopeHmac()).isEqualTo(first.scopeHmac());
            assertThat(next.idempotencyKeyHmac()).isEqualTo(first.idempotencyKeyHmac());
            assertThat(next.fingerprintHmac()).isNotEqualTo(first.fingerprintHmac());
        }
    }
    @Test void unsupportedOrAmbiguousManifestsAreRejectedBeforeHmac() {
        assertThatThrownBy(()->command(17,"foto.webp","image/webp",128,"b".repeat(64),"INGRESO")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->command(17,"foto\n.png","image/png",128,"b".repeat(64),"INGRESO")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->command(0,"foto.png","image/png",128,"b".repeat(64),"INGRESO")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->command(17,"foto.png","image/png",8000001,"b".repeat(64),"INGRESO")).isInstanceOf(IllegalArgumentException.class);
    }
}
