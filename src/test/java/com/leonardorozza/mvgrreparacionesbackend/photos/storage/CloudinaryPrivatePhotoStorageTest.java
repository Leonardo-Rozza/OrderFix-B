package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageException.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Real JDK HTTP transport against an owned loopback server, never a Cloudinary account. */
class CloudinaryPrivatePhotoStorageTest {
    private static final String CLOUD = "synthetic-cloud";
    private static final String API_KEY = "123456789";
    private static final String SECRET = "synthetic-private-secret";
    private static final String KEY = "ordenfix-private/37b29c5e-3704-4af1-a95b-355d888f0062";
    private static final String ASSET = "e5816b60a947d6c462b5adbdade21d6f";
    private static final String SECOND = "df048b68f26e237c928365dbb5283a677";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000), ZoneOffset.UTC);
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1cAAAAASUVORK5CYII=");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Observed> observed = new CopyOnWriteArrayList<>();
    private final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> releaseOnClose = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private URI origin;
    private CloudinaryPrivatePhotoStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                observed.add(new Observed(exchange.getRequestMethod(), exchange.getRequestURI(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
                Reply reply = replies.poll();
                if (reply == null) throw new AssertionError("Unexpected request to the local provider fixture");
                reply.respond(exchange);
            } catch (IOException expectedAfterCancellation) {
                // Client cancellation closes a response stream in the bounds/timeout tests.
            } catch (Throwable failure) {
                unexpected.add(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        storage = client(Duration.ofSeconds(3));
    }

    @AfterEach
    void close() throws InterruptedException {
        releaseOnClose.forEach(CountDownLatch::countDown);
        storage.close();
        server.stop(0);
        executor.shutdown();
        boolean terminated = executor.awaitTermination(3, TimeUnit.SECONDS);
        if (!terminated) {
            executor.shutdownNow();
            terminated = executor.awaitTermination(3, TimeUnit.SECONDS);
        }
        assertThat(terminated).isTrue();
        assertThat(unexpected).isEmpty();
    }

    @Test
    void uploadsOnlyBackendBytesWithPrivateImmutableOptionsAndAnOpaqueFilename() throws Exception {
        reply(200, "application/json", metadata());
        byte[] input = PNG.clone();
        assertThat(storage.upload(KEY, "image/png", input)).isEqualTo(asset());
        assertThat(input).containsExactly(PNG);
        assertThat(observed).hasSize(1);
        Observed request = observed.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("/v1_1/" + CLOUD + "/image/upload");
        assertThat(request.authorization()).isEqualTo(basic());
        assertThat(request.type()).startsWith("multipart/form-data; boundary=ordenfix-");
        String wire = new String(request.body(), StandardCharsets.ISO_8859_1);
        for (var option : Map.of("public_id", KEY, "type", "authenticated", "overwrite", "false",
                "allowed_formats", "jpg,png", "use_filename", "false", "unique_filename", "false",
                "discard_original_filename", "true").entrySet()) {
            assertThat(wire).contains("name=\"" + option.getKey() + "\"\r\n\r\n" + option.getValue() + "\r\n");
        }
        assertThat(wire).contains("name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n");
        int from = wire.indexOf("Content-Type: image/png\r\n\r\n") + "Content-Type: image/png\r\n\r\n".length();
        assertThat(Arrays.copyOfRange(request.body(), from, from + PNG.length)).containsExactly(PNG);
        assertThat(wire).doesNotContain(SECRET, "upload_preset", "transformation", "eager", "secure_url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "asset-id", "public", "video", "webp", "bytes", "fraction", "version", "version-string"})
    void rejectsMetadataWhichCannotIdentifyTheExpectedOriginal(String problem) throws Exception {
        ObjectNode json = metadataNode();
        switch (problem) {
            case "key" -> json.put("public_id", "another-key");
            case "asset-id" -> json.put("asset_id", "../foreign");
            case "public" -> json.put("type", "upload");
            case "video" -> json.put("resource_type", "video");
            case "webp" -> json.put("format", "webp");
            case "bytes" -> json.put("bytes", CloudinaryPrivatePhotoStorage.MAX_BYTES + 1L);
            case "fraction" -> json.put("bytes", 1.25);
            case "version" -> json.put("version", 0);
            case "version-string" -> json.put("version", "23");
        }
        reply(200, "application/json", JSON.writeValueAsBytes(json));
        safe(INVALID_ASSET, () -> storage.find(KEY));
    }

    @Test
    void uploadDoesNotAcceptAResponseWithADifferentMimeOrSize() throws Exception {
        ObjectNode wrongMime = metadataNode().put("format", "jpg");
        reply(200, "application/json", JSON.writeValueAsBytes(wrongMime));
        safe(INVALID_ASSET, () -> storage.upload(KEY, "image/png", PNG));
        reply(200, "application/json", JSON.writeValueAsBytes(metadataNode().put("bytes", PNG.length + 1)));
        safe(INVALID_ASSET, () -> storage.upload(KEY, "image/png", PNG));
    }

    @Test
    void findsOnlyTheEncodedAuthenticatedImageIdentityAndDistinguishesAbsenceFromFailure() throws Exception {
        reply(200, "application/json", metadata());
        assertThat(storage.find(KEY)).contains(asset());
        assertThat(observed.getFirst().uri().getRawPath()).endsWith("/resources/image/authenticated/ordenfix-private%2F37b29c5e-3704-4af1-a95b-355d888f0062");
        reply(404, "application/json", utf8("{\"error\":\"missing\"}"));
        assertThat(storage.find(KEY)).isEmpty();
        reply(401, "application/json", utf8("{\"error\":\"" + SECRET + "\"}"));
        safe(UNAVAILABLE, () -> storage.find(KEY));
    }

    @Test
    void readsOriginalThroughTheExpiringAssetApiAndNeverFollowsProviderUrls() throws Exception {
        ObjectNode details = metadataNode().put("secure_url", "https://untrusted.invalid/exfiltrate");
        reply(200, "application/json", JSON.writeValueAsBytes(details));
        reply(200, "image/png", PNG);
        reply(200, "application/json", JSON.writeValueAsBytes(details));
        assertThat(storage.read(asset(), PNG.length)).containsExactly(PNG);
        assertThat(observed).hasSize(3);
        assertThat(observed.getFirst().uri().getPath()).isEqualTo("/v1_1/" + CLOUD + "/resources/" + ASSET);
        Observed download = observed.get(1);
        assertThat(download.method()).isEqualTo("GET");
        assertThat(download.uri().getPath()).isEqualTo("/v1_1/" + CLOUD + "/asset/download");
        assertThat(download.authorization()).isNull();
        Map<String, String> query = query(download.uri());
        assertThat(query).containsOnlyKeys("asset_id", "expires_at", "timestamp", "api_key", "signature");
        assertThat(query).containsEntry("asset_id", ASSET).containsEntry("expires_at", "1800000060")
                .containsEntry("timestamp", "1800000000").containsEntry("api_key", API_KEY);
        String signed = "asset_id=" + ASSET + "&expires_at=1800000060&timestamp=1800000000" + SECRET;
        assertThat(query.get("signature")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(signed))));
        assertThat(observed.getLast().uri()).isEqualTo(observed.getFirst().uri());
    }

    @Test
    void readStopsBeforeDownloadOnIdentityDriftAndAfterDownloadOnVersionDrift() throws Exception {
        reply(200, "application/json", JSON.writeValueAsBytes(metadataNode().put("asset_id", SECOND)));
        safe(INVALID_ASSET, () -> storage.read(asset(), PNG.length));
        assertThat(observed).hasSize(1);
        reply(200, "application/json", metadata());
        reply(200, "image/png", PNG);
        reply(200, "application/json", JSON.writeValueAsBytes(metadataNode().put("version", 24)));
        safe(INVALID_ASSET, () -> storage.read(asset(), PNG.length));
        assertThat(observed).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"too-large", "short", "mime", "missing"})
    void refusesUnboundedOrInconsistentBinaryResponses(String problem) throws Exception {
        reply(200, "application/json", metadata());
        if (problem.equals("too-large")) reply(200, "image/png", new byte[PNG.length + 1]);
        if (problem.equals("short")) reply(200, "image/png", new byte[PNG.length - 1]);
        if (problem.equals("mime")) reply(200, "text/html", PNG);
        if (problem.equals("missing")) reply(404, "application/json", utf8("{}"));
        safe(problem.equals("too-large") ? UNAVAILABLE : problem.equals("missing") ? MISSING : INVALID_ASSET,
                () -> storage.read(asset(), PNG.length));
        assertThat(observed).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletesByImmutableAssetIdAndConfirmsAbsenceWithoutDeletingAReplacement(boolean publicIdResponse) throws Exception {
        reply(200, "application/json", metadata());
        String resultKey = publicIdResponse ? KEY : ASSET;
        reply(200, "application/json", utf8("{\"deleted\":{\"" + resultKey + "\":\"deleted\"}}"));
        reply(404, "application/json", utf8("{}"));
        storage.delete(KEY, ASSET);
        assertThat(observed).hasSize(3);
        Observed delete = observed.get(1);
        assertThat(delete.method()).isEqualTo("DELETE");
        assertThat(delete.uri().getPath()).isEqualTo("/v1_1/" + CLOUD + "/resources");
        assertThat(delete.authorization()).isEqualTo(basic());
        assertThat(new String(delete.body(), StandardCharsets.UTF_8)).isEqualTo("asset_ids%5B%5D=" + ASSET + "&invalidate=true");
        // An already deleted asset is idempotent even if its old public_id was reused.
        reply(404, "application/json", utf8("{}"));
        storage.delete(KEY, ASSET);
        assertThat(observed).hasSize(4);
        assertThat(observed.stream().filter(r -> r.method().equals("DELETE"))).hasSize(1);
    }

    @Test
    void refusesDeletionOfAnAssetOutsideTheAccreditedKey() throws Exception {
        reply(200, "application/json", JSON.writeValueAsBytes(metadataNode().put("public_id", "another-key")));
        safe(INVALID_ASSET, () -> storage.delete(KEY, ASSET));
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst().method()).isEqualTo("GET");
    }

    @Test
    void aDeleteAcknowledgmentForAnotherIdentityIsRejected() throws Exception {
        reply(200, "application/json", metadata());
        reply(200, "application/json", utf8("{\"deleted\":{\"unrelated\":\"deleted\"}}"));
        safe(UNAVAILABLE, () -> storage.delete(KEY, ASSET));
        assertThat(observed).hasSize(2);
    }

    @Test
    void aDeleteAcknowledgmentIsNotEnoughWhileTheAssetIsStillPresent() throws Exception {
        reply(200, "application/json", metadata());
        reply(200, "application/json", utf8("{\"deleted\":{\"" + ASSET + "\":\"deleted\"}}"));
        reply(200, "application/json", metadata());
        safe(UNAVAILABLE, () -> storage.delete(KEY, ASSET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "trailing", "oversized", "utf8", "wrong-type", "encoding"})
    void parsesOnlyBoundedUnambiguousProviderJson(String problem) throws Exception {
        byte[] body = metadata();
        String type = "application/json";
        switch (problem) {
            case "duplicate" -> body = utf8(new String(body, StandardCharsets.UTF_8).replace("\"type\":\"authenticated\"", "\"type\":\"authenticated\",\"type\":\"upload\""));
            case "trailing" -> body = utf8(new String(body, StandardCharsets.UTF_8) + "{}");
            case "oversized" -> body = utf8(" ".repeat(262_145));
            case "utf8" -> body = new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28, '"', ':', '1', '}'};
            case "wrong-type" -> type = "text/html";
            case "encoding" -> { }
        }
        if (problem.equals("encoding")) {
            byte[] payload = body;
            replies.add(exchange -> {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                respond(exchange, 200, "application/json", payload);
            });
        } else reply(200, type, body);
        safe(UNAVAILABLE, () -> storage.find(KEY));
    }

    @Test
    void neverFollowsRedirectsOrForwardsCredentialsToTheirTargets() {
        replies.add(exchange -> {
            exchange.getResponseHeaders().add("Location", origin + "/must-not-follow");
            respond(exchange, 302, "application/json", utf8("{}"));
        });
        safe(UNAVAILABLE, () -> storage.find(KEY));
        assertThat(observed).hasSize(1);
    }

    @Test
    void timeoutIncludesABodyStalledAfterSuccessfulHeaders() throws Exception {
        storage.close();
        storage = client(Duration.ofSeconds(1));
        CountDownLatch headersSent = new CountDownLatch(1), release = new CountDownLatch(1);
        releaseOnClose.add(release);
        replies.add(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            release.await(3, TimeUnit.SECONDS);
        });
        long started = System.nanoTime();
        safe(UNAVAILABLE, () -> storage.find(KEY));
        assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2800));
    }

    @Test
    void cancellationRestoresTheCallingThreadsInterruptFlag() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        releaseOnClose.add(release);
        replies.add(exchange -> {
            received.countDown();
            release.await(3, TimeUnit.SECONDS);
        });
        AtomicReference<Throwable> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread caller = Thread.ofPlatform().start(() -> {
            try { storage.find(KEY); }
            catch (Throwable failure) { result.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOf(PrivatePhotoStorageException.class);
            assertThat(((PrivatePhotoStorageException) result.get()).reason()).isEqualTo(UNAVAILABLE);
            assertThat(interrupted.get()).isTrue();
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(2000);
        }
    }

    @Test
    void invalidInputsAndClosedStorageCannotBorrowTheTransport() {
        for (String key : List.of("", "../foreign", "a//b", "/root", "https://foreign.invalid", "a?b", "a".repeat(201))) {
            safe(INVALID_ASSET, () -> storage.find(key));
        }
        safe(INVALID_ASSET, () -> storage.upload(KEY, "image/webp", PNG));
        safe(INVALID_ASSET, () -> storage.upload(KEY, "image/png", new byte[0]));
        safe(INVALID_ASSET, () -> storage.upload(KEY, "image/png", new byte[8_000_001]));
        safe(INVALID_ASSET, () -> storage.read(asset(), PNG.length - 1));
        safe(INVALID_ASSET, () -> storage.read(asset(), 8_000_001));
        safe(INVALID_ASSET, () -> storage.delete(KEY, "../foreign"));
        storage.close();
        storage.close();
        safe(UNAVAILABLE, () -> storage.find(KEY));
        assertThat(observed).isEmpty();
        assertThat(storage.toString()).isEqualTo("CloudinaryPrivatePhotoStorage[redacted]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1:1234", "http://localhost:1234", "http://127.0.0.1", "http://127.0.0.1:1234/path", "http://user@127.0.0.1:1234", "http://127.0.0.1:1234?query", "http://127.0.0.1:1234#fragment", "https://foreign.invalid"})
    void endpointSeamCannotAuthorizeAnArbitraryAddress(String url) {
        assertThatThrownBy(() -> new CloudinaryPrivatePhotoStorage(CLOUD, API_KEY, SECRET, URI.create(url), Duration.ofSeconds(1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("La configuración de almacenamiento privado no es válida.").hasNoCause();
    }

    private CloudinaryPrivatePhotoStorage client(Duration timeout) {
        return new CloudinaryPrivatePhotoStorage(CLOUD, API_KEY, SECRET, origin, timeout, CLOCK);
    }

    private static PrivatePhotoStorage.StoredAsset asset() { return new PrivatePhotoStorage.StoredAsset(ASSET, KEY, "image/png", PNG.length, "23"); }
    private static ObjectNode metadataNode() {
        ObjectNode node = JSON.createObjectNode();
        node.put("asset_id", ASSET).put("public_id", KEY).put("resource_type", "image").put("type", "authenticated")
                .put("format", "png").put("bytes", PNG.length).put("version", 23);
        return node;
    }
    private static byte[] metadata() throws IOException { return JSON.writeValueAsBytes(metadataNode()); }
    private static String basic() { return "Basic " + Base64.getEncoder().encodeToString(utf8(API_KEY + ":" + SECRET)); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private void reply(int status, String type, byte[] body) { replies.add(exchange -> respond(exchange, status, type, body)); }
    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
    private static void safe(PrivatePhotoStorageException.Reason reason, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PrivatePhotoStorageException.class, error -> {
            assertThat(error.reason()).isEqualTo(reason);
            assertThat(error.getMessage()).isEqualTo("No se pudo completar la operación de almacenamiento privado.");
            assertThat(error.getCause()).isNull();
            assertThat(error.getSuppressed()).isEmpty();
        });
    }
    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : uri.getRawQuery().split("&")) {
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            assertThat(values.put(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8))).isNull();
        }
        return values;
    }
    private record Observed(String method, URI uri, String authorization, String type, byte[] body) { }
    @FunctionalInterface private interface Reply { void respond(HttpExchange exchange) throws Exception; }
}
