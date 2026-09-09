package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageException.Reason.*;

/** Server-to-server only. Provider URLs, filenames and response payloads never become application data. */
public final class CloudinaryPrivatePhotoStorage implements PrivatePhotoStorage, AutoCloseable {
    static final int MAX_BYTES = 8_000_000;
    private static final int MAX_JSON = 262_144;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(8192).maxNameLength(128).maxNumberLength(32).build()).build());
    private final HttpClient client;
    private final URI base;
    private final String apiKey;
    private final String secret;
    private final String authorization;
    private final Clock clock;
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    CloudinaryPrivatePhotoStorage(String cloudName, String apiKey, String secret) {
        this(cloudName, apiKey, secret, URI.create("https://api.cloudinary.com"), TIMEOUT, Clock.systemUTC(), false);
    }

    /** Local transport fixture only; production offers no configurable endpoint or redirect policy. */
    CloudinaryPrivatePhotoStorage(String cloudName, String apiKey, String secret,
                                 URI loopback, Duration timeout, Clock clock) {
        this(cloudName, apiKey, secret, loopback, timeout, clock, true);
    }

    private CloudinaryPrivatePhotoStorage(String cloudName, String apiKey, String secret,
                                         URI origin, Duration timeout, Clock clock, boolean local) {
        if (cloudName == null || !ID.matcher(cloudName).matches()
                || apiKey == null || !ID.matcher(apiKey).matches() || !validSecret(secret)
                || timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(TIMEOUT) > 0
                || clock == null || origin == null
                || (local && !("http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost())
                    && origin.getPort() > 0 && origin.getPort() <= 65535
                    && origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))))) {
            throw new IllegalArgumentException("La configuración de almacenamiento privado no es válida.");
        }
        this.base = URI.create(origin.getScheme() + "://" + origin.getRawAuthority() + "/v1_1/" + cloudName + "/");
        this.apiKey = apiKey;
        this.secret = secret;
        byte[] credentials = (apiKey + ":" + secret).getBytes(StandardCharsets.UTF_8);
        try {
            authorization = "Basic " + Base64.getEncoder().encodeToString(credentials);
        } finally {
            Arrays.fill(credentials, (byte) 0);
        }
        this.clock = clock;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout.compareTo(Duration.ofSeconds(5)) < 0 ? timeout : Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }

    @Override
    public StoredAsset upload(String objectKey, String mimeType, byte[] content) {
        Deadline deadline = deadline();
        key(objectKey);
        String format = format(mimeType);
        if (content == null || content.length == 0 || content.length > MAX_BYTES) throw failure(INVALID_ASSET);
        String boundary = "ordenfix-" + UUID.randomUUID();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("public_id", objectKey);
        fields.put("type", "authenticated");
        fields.put("overwrite", "false");
        fields.put("allowed_formats", "jpg,png");
        fields.put("use_filename", "false");
        fields.put("unique_filename", "false");
        fields.put("discard_original_filename", "true");
        List<byte[]> parts = new ArrayList<>();
        fields.forEach((name, value) -> parts.add(utf8("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n")));
        parts.add(utf8("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo."
                + format + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n"));
        byte[] owned = content.clone();
        parts.add(owned);
        parts.add(utf8("\r\n--" + boundary + "--\r\n"));
        try {
            HttpRequest request = request("image/upload", deadline).header("Authorization", authorization)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build();
            HttpResponse<byte[]> response = send(request, MAX_JSON, deadline);
            success(response);
            StoredAsset uploaded = asset(json(response), objectKey);
            if (!uploaded.mimeType().equals(mimeType) || uploaded.bytes() != content.length) throw failure(INVALID_ASSET);
            // A successful overwrite=false response may refer to an earlier upload. The business
            // finalizer separately reads the original and verifies its SHA-256 before association.
            return uploaded;
        } finally {
            Arrays.fill(owned, (byte) 0);
        }
    }

    @Override
    public Optional<StoredAsset> find(String objectKey) {
        return find(objectKey, deadline());
    }

    private Optional<StoredAsset> find(String objectKey, Deadline deadline) {
        key(objectKey);
        HttpResponse<byte[]> response = send(request("resources/image/authenticated/" + encoded(objectKey), deadline)
                .header("Authorization", authorization).GET().build(), MAX_JSON, deadline);
        if (response.statusCode() == 404) return Optional.empty();
        success(response);
        return Optional.of(asset(json(response), objectKey));
    }

    private Optional<StoredAsset> findById(String assetId, String objectKey, Deadline deadline) {
        id(assetId);
        HttpResponse<byte[]> response = send(request("resources/" + encoded(assetId), deadline)
                .header("Authorization", authorization).GET().build(), MAX_JSON, deadline);
        if (response.statusCode() == 404) return Optional.empty();
        success(response);
        StoredAsset found = asset(json(response), objectKey);
        if (!found.assetId().equals(assetId)) throw failure(INVALID_ASSET);
        return Optional.of(found);
    }

    @Override
    public byte[] read(StoredAsset expected, int maxBytes) {
        Deadline deadline = deadline();
        valid(expected);
        if (maxBytes < 1 || maxBytes > MAX_BYTES || expected.bytes() > maxBytes) throw failure(INVALID_ASSET);
        if (!findById(expected.assetId(), expected.objectKey(), deadline).orElseThrow(() -> failure(MISSING)).equals(expected)) {
            throw failure(INVALID_ASSET);
        }
        long now = clock.instant().getEpochSecond();
        Map<String, String> signed = new TreeMap<>();
        signed.put("asset_id", expected.assetId());
        signed.put("expires_at", Long.toString(Math.addExact(now, 60)));
        signed.put("timestamp", Long.toString(now));
        String signature = signature(signed);
        signed.put("api_key", apiKey);
        signed.put("signature", signature);
        // No format or transformation: return the original bytes. This URL is local to
        // the transport and is never taken from provider JSON or returned to a caller.
        HttpResponse<byte[]> response = send(request("asset/download?" + form(signed), deadline)
                .GET().build(), maxBytes, deadline);
        if (response.statusCode() == 404) throw failure(MISSING);
        success(response);
        byte[] result = response.body();
        try {
            if (result.length != expected.bytes() || !contentType(response).equals(expected.mimeType())) throw failure(INVALID_ASSET);
            if (!findById(expected.assetId(), expected.objectKey(), deadline).orElseThrow(() -> failure(MISSING)).equals(expected)) {
                throw failure(INVALID_ASSET);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            Arrays.fill(result, (byte) 0);
            throw failure;
        }
    }

    @Override
    public void delete(String objectKey, String assetId) {
        Deadline deadline = deadline();
        key(objectKey);
        id(assetId);
        Optional<StoredAsset> existing = findById(assetId, objectKey, deadline);
        if (existing.isEmpty()) return;
        // Delete by immutable identity, never by a public_id which might be replaced
        // after the lookup. A mismatching object key fails before the DELETE request.
        String body = "asset_ids%5B%5D=" + encoded(assetId) + "&invalidate=true";
        HttpResponse<byte[]> response = send(request("resources", deadline).header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(), MAX_JSON, deadline);
        success(response);
        JsonNode deleted = json(response).get("deleted");
        if (deleted == null || !deleted.isObject() || deleted.size() != 1) throw failure(UNAVAILABLE);
        // The documented Admin response names the public ID even when deletion uses asset_ids.
        // Both names below belong to the exact receipt already checked; the final lookup by
        // immutable asset ID, rather than this acknowledgment, establishes absence.
        String resultKey = deleted.has(objectKey) ? objectKey : assetId;
        if (!deleted.has(resultKey)
                || !("deleted".equals(text(deleted, resultKey)) || "not_found".equals(text(deleted, resultKey)))) {
            throw failure(UNAVAILABLE);
        }
        if (findById(assetId, objectKey, deadline).isPresent()) throw failure(UNAVAILABLE);
    }

    private StoredAsset asset(JsonNode root, String objectKey) {
        if (!root.isObject() || !objectKey.equals(text(root, "public_id"))
                || !"image".equals(text(root, "resource_type")) || !"authenticated".equals(text(root, "type"))) {
            throw failure(INVALID_ASSET);
        }
        String assetId = text(root, "asset_id");
        id(assetId);
        String mime = switch (text(root, "format")) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> throw failure(INVALID_ASSET);
        };
        JsonNode bytes = root.get("bytes"), version = root.get("version");
        if (bytes == null || !bytes.isIntegralNumber() || !bytes.canConvertToLong() || bytes.longValue() < 1
                || bytes.longValue() > MAX_BYTES || version == null || !version.isIntegralNumber()
                || !version.canConvertToLong() || version.longValue() < 1) throw failure(INVALID_ASSET);
        return new StoredAsset(assetId, objectKey, mime, bytes.longValue(), version.asText());
    }

    private static JsonNode json(HttpResponse<byte[]> response) {
        if (!contentType(response).equals("application/json")) throw failure(UNAVAILABLE);
        try (var parser = JSON.createParser(response.body())) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw failure(UNAVAILABLE);
            return root;
        } catch (IOException | IllegalArgumentException failure) {
            throw failure(UNAVAILABLE);
        }
    }

    private static String contentType(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("Content-Type");
        if (values.size() != 1) throw failure(UNAVAILABLE);
        String value = values.getFirst();
        if (value.length() > 256) throw failure(UNAVAILABLE);
        return value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
    }

    private HttpRequest.Builder request(String relative, Deadline deadline) {
        return HttpRequest.newBuilder(base.resolve(relative)).timeout(Duration.ofNanos(deadline.remaining()))
                .header("Accept-Encoding", "identity");
    }

    private HttpResponse<byte[]> send(HttpRequest request, int limit, Deadline deadline) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, ignored -> new LimitedBody(limit));
            HttpResponse<byte[]> response = pending.get(deadline.remaining(), TimeUnit.NANOSECONDS);
            deadline.remaining();
            if (response.headers().firstValue("Content-Encoding").filter(v -> !v.equalsIgnoreCase("identity")).isPresent()) {
                throw failure(UNAVAILABLE);
            }
            return response;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure(UNAVAILABLE);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            throw failure(UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private Deadline deadline() {
        if (closed.get()) throw failure(UNAVAILABLE);
        return new Deadline(timeout.toNanos());
    }

    private static void success(HttpResponse<?> response) {
        if (response.statusCode() != 200) throw failure(UNAVAILABLE);
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw failure(INVALID_ASSET);
        return value.textValue();
    }

    private static void valid(StoredAsset asset) {
        if (asset == null) throw failure(INVALID_ASSET);
        key(asset.objectKey());
        id(asset.assetId());
        format(asset.mimeType());
        if (asset.bytes() < 1 || asset.bytes() > MAX_BYTES || asset.version() == null
                || !asset.version().matches("[1-9][0-9]{0,18}")) throw failure(INVALID_ASSET);
    }

    private static void key(String value) {
        if (value == null || value.length() > 200 || !KEY.matcher(value).matches()) throw failure(INVALID_ASSET);
    }

    private static void id(String value) {
        if (value == null || !ID.matcher(value).matches()) throw failure(INVALID_ASSET);
    }

    private static String format(String mime) {
        if (mime == null) throw failure(INVALID_ASSET);
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> throw failure(INVALID_ASSET);
        };
    }

    private static boolean validSecret(String value) {
        return value != null && !value.isEmpty() && value.length() <= 256
                && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }

    private String signature(Map<String, String> params) {
        StringBuilder input = new StringBuilder();
        params.forEach((key, value) -> {
            if (!input.isEmpty()) input.append('&');
            input.append(key).append('=').append(value);
        });
        byte[] bytes = utf8(input.append(secret).toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw failure(UNAVAILABLE);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> encoded(e.getKey()) + "=" + encoded(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static PrivatePhotoStorageException failure(PrivatePhotoStorageException.Reason reason) {
        return new PrivatePhotoStorageException(reason);
    }

    @Override public String toString() { return "CloudinaryPrivatePhotoStorage[redacted]"; }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) client.shutdownNow();
    }

    private static final class Deadline {
        private final long started = System.nanoTime();
        private final long nanos;
        private Deadline(long nanos) { this.nanos = nanos; }
        private long remaining() {
            long result = nanos - (System.nanoTime() - started);
            if (result <= 0) throw failure(UNAVAILABLE);
            return result;
        }
    }

    /** Bounded aggregation; cancellation prevents an unbounded or stalled response from accumulating. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(failure(UNAVAILABLE));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                Arrays.fill(chunk, (byte) 0);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(failure(UNAVAILABLE)); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
