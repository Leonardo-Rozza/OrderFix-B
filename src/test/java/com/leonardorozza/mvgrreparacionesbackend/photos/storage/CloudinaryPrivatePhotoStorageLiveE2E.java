package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Opt-in provider check: *E2E is excluded from the ordinary *Test / *IT suites.
 * Owns only two synthetic assets, never application accounts or historical objects. */
class CloudinaryPrivatePhotoStorageLiveE2E {
    private static final String PREFIX = "photos.private.cloudinary.";

    @BeforeAll
    static void requireExplicitOptIn() {
        if (!"synthetic-only".equals(System.getenv("ORDENFIX_CLOUDINARY_LIVE_CHECK"))
                || System.getenv("ORDENFIX_CLOUDINARY_CREDENTIALS_FILE") == null
                || System.getenv("ORDENFIX_CLOUDINARY_CREDENTIALS_FILE").isBlank()) {
            throw new AssertionError("Cloudinary live check requires explicit synthetic-only opt-in and credentials file.");
        }
    }

    @ParameterizedTest(name = "authenticated synthetic {0}")
    @ValueSource(strings = {"png", "jpg"})
    void verifiesPrivateOriginalAnonymousDenialAndDeletion(String format) throws Exception {
        Run run = new Run(format);
        try {
            Map<String, Object> settings = credentials();
            String cloud = (String) settings.get(PREFIX + "cloud-name");
            try (AnnotationConfigApplicationContext context = providerContext(settings)) {
                PrivatePhotoStorage storage = context.getBean(PrivatePhotoStorage.class);
                PrivatePhotoStorage.StoredAsset asset = null;
                boolean attempted = false;
                boolean deleted = false;
                try {
                    byte[] original = syntheticImage(format);
                    String mime = format.equals("png") ? "image/png" : "image/jpeg";
                    run.stage("checking-unique-key");
                    require(storage.find(run.key).isEmpty());
                    run.stage("uploading");
                    attempted = true;
                    asset = storage.upload(run.key, mime, original);
                    run.stage("checking-authenticated-identity");
                    // The real adapter accepts only resource_type=image and type=authenticated.
                    require(storage.find(run.key).orElseThrow().equals(asset));
                    require(asset.mimeType().equals(mime) && asset.bytes() == original.length);
                    run.stage("reading-private-original");
                    byte[] downloaded = storage.read(asset, original.length);
                    require(Arrays.equals(original, downloaded));
                    require(Arrays.equals(sha256(original), sha256(downloaded)));
                    Arrays.fill(downloaded, (byte) 0);
                    try (HttpClient anonymous = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(5)).build()) {
                        anonymousProbe(anonymous, cloud, asset, format, "", "original", run);
                        anonymousProbe(anonymous, cloud, asset, format, "c_scale,w_32/", "transformed", run);
                    }
                    run.stage("checking-asset-still-present");
                    require(storage.find(run.key).orElseThrow().equals(asset));
                    require(Arrays.equals(original, storage.read(asset, original.length)));
                    run.stage("deleting-owned-asset");
                    storage.delete(run.key, asset.assetId());
                    require(storage.find(run.key).isEmpty());
                    run.stage("checking-idempotent-delete");
                    storage.delete(run.key, asset.assetId());
                    run.stage("checking-missing-read");
                    try {
                        storage.read(asset, original.length);
                        throw new AssertionError("Deleted asset remained readable.");
                    } catch (PrivatePhotoStorageException missing) {
                        require(missing.reason() == PrivatePhotoStorageException.Reason.MISSING);
                    }
                    deleted = true;
                    Arrays.fill(original, (byte) 0);
                    run.stage("passed-and-deleted");
                } catch (Exception | AssertionError failure) {
                    run.fail(); // Do not propagate provider causes, payloads, URLs or credentials.
                } finally {
                    if (attempted && !deleted) {
                        try {
                            run.cleanupStage("recovering-owned-asset-for-cleanup");
                            // An uncertain upload ACK may leave the one prechecked UUID key present.
                            if (asset == null) asset = storage.find(run.key).orElse(null);
                            if (asset != null) storage.delete(run.key, asset.assetId());
                            require(storage.find(run.key).isEmpty());
                            run.cleanupPending = asset == null;
                            run.cleanupStage(asset == null ? "unacknowledged-upload-key-currently-absent" : "cleanup-confirmed");
                        } catch (Exception | AssertionError cleanup) {
                            run.fail();
                            run.cleanupPending = true;
                            run.cleanupStage("cleanup-required-for-owned-key");
                        }
                    }
                }
            } finally {
                settings.clear();
            }
        } catch (Exception | AssertionError failure) {
            run.fail();
        }
        if (run.failedStage != null) {
            throw new AssertionError("Cloudinary synthetic check failed: run=" + run.id
                    + " stage=" + run.failedStage + " cleanupPending=" + run.cleanupPending);
        }
    }
    private static Map<String, Object> credentials() throws Exception {
        Properties file = new Properties();
        try (Reader input = Files.newBufferedReader(
                Path.of(System.getenv("ORDENFIX_CLOUDINARY_CREDENTIALS_FILE")), StandardCharsets.UTF_8)) {
            file.load(input);
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("photos.private.enabled", "true");
            result.put(PREFIX + "cloud-name", credential(file, "cloud-name", "CLOUD_NAME"));
            result.put(PREFIX + "api-key", credential(file, "api-key", "API_KEY"));
            result.put(PREFIX + "api-secret", credential(file, "api-secret", "API_SECRET"));
            return result;
        } finally {
            file.clear();
        }
    }
    private static String credential(Properties file, String suffix, String alias) {
        String value = file.getProperty(PREFIX + suffix);
        if (value == null || value.equals("${" + alias + "}")) value = file.getProperty(alias);
        require(value != null && !value.isBlank() && !value.contains("${"));
        return value;
    }
    private static AnnotationConfigApplicationContext providerContext(Map<String, Object> settings) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-provider", settings));
            context.register(PrivatePhotoStorageConfiguration.class);
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw new AssertionError("Could not create the isolated provider adapter.");
        }
    }
    private static void anonymousProbe(HttpClient client, String cloud, PrivatePhotoStorage.StoredAsset asset,
                                       String format, String transformation, String name, Run run) throws Exception {
        // Cloudinary's canonical versioned authenticated delivery path; no signature or credentials.
        URI uri = URI.create("https://res.cloudinary.com/" + cloud + "/image/authenticated/"
                + transformation + "v" + asset.version() + "/" + asset.objectKey() + "." + format);
        run.stage("anonymous-" + name);
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        run.stage("anonymous-" + name + "-http-" + status);
        // A 404 could be an absent/wrong delivery variant; it does not establish an ACL denial.
        if (status == 404) run.stage("anonymous-" + name + "-inconclusive-404");
        require(status == 401 || status == 403);
    }
    private static byte[] syntheticImage(String format) throws Exception {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, ((x * 4) << 16) | ((y * 5) << 8) | 96);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        require(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Synthetic provider check failed.");
    }
    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final String key = "ordenfix-verificacion/" + id;
        final Path journal;
        String currentStage = "preparing";
        String failedStage;
        boolean cleanupPending;

        Run(String format) throws Exception {
            journal = Files.createTempFile("ordenfix-cloudinary-live-" + id + "-", ".log");
            Files.writeString(journal, "run=" + id + " format=" + format + " key=" + key + "\n");
            System.out.println("Cloudinary synthetic check run=" + id + " journal=" + journal);
            stage("preparing");
        }

        void stage(String value) throws IOException {
            currentStage = value;
            System.out.println("Cloudinary synthetic check run=" + id + " stage=" + value);
            Files.writeString(journal, value + "\n", StandardOpenOption.APPEND);
        }

        void cleanupStage(String value) {
            try { stage(value); } catch (IOException failure) { fail(); }
        }

        void fail() {
            if (failedStage == null) failedStage = currentStage;
        }
    }
}
