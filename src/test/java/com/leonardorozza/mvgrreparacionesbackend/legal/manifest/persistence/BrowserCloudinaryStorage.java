package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Test-only guard around the actual adapter. Owns at most four freshly checked UUID keys.
 * Provider failures are never rendered, retried as uploads, or mistaken for the induced lost ACK. */
final class BrowserCloudinaryStorage implements PrivatePhotoStorage, AutoCloseable {
    static final String OPT_IN = "ORDENFIX_PHOTOS_BROWSER_CLOUDINARY";
    static final String CREDENTIALS = "ORDENFIX_CLOUDINARY_CREDENTIALS_FILE";
    private static final String PREFIX = "photos.private.cloudinary.";
    private static final int MAX_ASSETS = 4;
    private final PrivatePhotoStorage delegate;
    private final Function<UUID, Expected> ownership;
    private final AnonymousProbe anonymous;
    private final Runnable closeAdapter;
    private final Path journal;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean stopping, closed, cleanupAttempted;

    record Expected(long bytes, String sha256, String mimeType) {
        Expected {
            if (bytes < 1 || bytes > 65_536 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                    || !"image/png".equals(mimeType)) throw unavailable();
        }
        @Override public String toString() { return "Expected[synthetic-photo]"; }
    }
    record ProbeResult(int original, int transformed) { }
    @FunctionalInterface interface AnonymousProbe { ProbeResult probe(StoredAsset asset) throws Exception; }

    static boolean enabled(Map<String, String> environment) {
        String mode = environment.get(OPT_IN);
        if (mode == null) return false;
        if (!"synthetic-only".equals(mode) || environment.get(CREDENTIALS) == null
                || environment.get(CREDENTIALS).isBlank()) throw configuration();
        return true;
    }

    static BrowserCloudinaryStorage open(Map<String, String> environment, UUID run,
                                         Function<UUID, Expected> ownership) {
        if (!enabled(environment)) throw configuration();
        Map<String, Object> settings;
        try { settings = settings(Path.of(environment.get(CREDENTIALS))); }
        catch (RuntimeException failure) { throw configuration(); }
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            // No ambient property source, import, Boot application, provider URL, or unrelated secret.
            List<String> names = new ArrayList<>();
            context.getEnvironment().getPropertySources().forEach(source -> names.add(source.getName()));
            names.forEach(context.getEnvironment().getPropertySources()::remove);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-photo-provider", settings));
            context.register(PrivatePhotoStorageConfiguration.class);
            context.refresh();
            String cloud = (String) settings.get(PREFIX + "cloud-name");
            Path directory = Files.createTempDirectory("ordenfix-cloudinary-browser-" + run + "-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path journal = Files.createFile(directory.resolve("assets.log"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            BrowserCloudinaryStorage result = new BrowserCloudinaryStorage(context.getBean(PrivatePhotoStorage.class),
                    ownership, asset -> probe(cloud, asset), journal, context::close);
            result.event(null, "run-" + run);
            System.out.println("Cloudinary browser synthetic journal=" + journal);
            return result;
        } catch (Exception | AssertionError failure) {
            try { context.close(); } catch (RuntimeException ignored) { }
            throw configuration();
        } finally { settings.clear(); }
    }

    /** Only these three values (canonical spelling or their existing aliases) enter memory/configuration. */
    static Map<String, Object> settings(Path file) {
        Properties selected = new Properties();
        Set<String> allowed = Set.of(PREFIX + "cloud-name", PREFIX + "api-key", PREFIX + "api-secret", "CLOUD_NAME", "API_KEY", "API_SECRET");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 1_048_576) throw configuration();
            try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.length() > 16_384) throw configuration();
                    String trimmed = line.stripLeading();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
                    String key = trimmed.split("[\\s=:]", 2)[0];
                    if (!allowed.contains(key)) continue;
                    Properties one = new Properties();
                    try {
                        one.load(new StringReader(line));
                        if (one.size() != 1 || !one.containsKey(key) || selected.containsKey(key)) throw configuration();
                        selected.setProperty(key, one.getProperty(key));
                    } finally { one.clear(); }
                }
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("photos.private.enabled", "true");
            for (String[] pair : List.of(new String[]{"cloud-name", "CLOUD_NAME"}, new String[]{"api-key", "API_KEY"}, new String[]{"api-secret", "API_SECRET"})) {
                String value = selected.getProperty(PREFIX + pair[0]);
                if (value == null || value.equals("${" + pair[1] + "}")) value = selected.getProperty(pair[1]);
                if (value == null || value.isBlank() || value.contains("${")) throw configuration();
                settings.put(PREFIX + pair[0], value);
            }
            return settings;
        } catch (Exception failure) { throw configuration(); }
        finally { selected.clear(); }
    }

    BrowserCloudinaryStorage(PrivatePhotoStorage delegate, Function<UUID, Expected> ownership,
                             AnonymousProbe anonymous, Path journal, Runnable closeAdapter) {
        this.delegate = Objects.requireNonNull(delegate); this.ownership = Objects.requireNonNull(ownership);
        this.anonymous = Objects.requireNonNull(anonymous); this.journal = Objects.requireNonNull(journal);
        this.closeAdapter = Objects.requireNonNull(closeAdapter);
    }

    @Override public synchronized Optional<StoredAsset> find(String key) {
        Entry entry = entry(key);
        try {
            entry.findCalls++;
            Optional<StoredAsset> found = delegate.find(key);
            if (!entry.attempted) {
                if (found.isPresent()) { entry.collision = true; event(entry, "preexisting-key-not-owned"); throw unavailable(); }
                entry.prechecked = true;
                return Optional.empty();
            }
            if (entry.receipt == null || entry.failed) throw unavailable();
            if (found.isPresent() && !entry.receipt.equals(found.get())) throw unavailable();
            if (found.isEmpty() && !entry.deleted) throw unavailable();
            return found;
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override public synchronized StoredAsset upload(String key, String mime, byte[] bytes) {
        Entry entry = entry(key);
        if (entry.attempted || entry.collision || !entry.prechecked || !entry.expected.mimeType().equals(mime)) throw unavailable();
        verifyBytes(entry, bytes);
        try {
            // Recheck immediately before the only upload; never claim or delete a pre-existing collision.
            if (delegate.find(key).isPresent()) { entry.collision = true; event(entry, "preexisting-key-not-owned"); throw unavailable(); }
            event(entry, "upload-attempt");
            entry.attempted = true;
            StoredAsset receipt = delegate.upload(key, mime, bytes);
            verifyReceipt(entry, receipt);
            entry.receipt = receipt; // Retain identity before any synthetic ACK loss or journal error.
            event(entry, "upload-acknowledged");
            entry.inducedAckLoss = true;
            event(entry, "ack-loss-induced");
        } catch (RuntimeException failure) {
            entry.failed = true;
            eventQuietly(entry, "upload-failed-cleanup-required");
            throw unavailable();
        }
        throw unavailable(); // One intentional, explicit failure after the real provider accepted the asset.
    }

    @Override public synchronized byte[] read(StoredAsset expected, int maxBytes) {
        Entry entry = entry(expected.objectKey());
        if (entry.failed || entry.deleted || !expected.equals(entry.receipt)) throw unavailable();
        byte[] bytes = null;
        try {
            bytes = delegate.read(expected, maxBytes);
            verifyBytes(entry, bytes);
            entry.readCalls++;
            if (!entry.probed) {
                if (!delegate.find(entry.key).filter(expected::equals).isPresent()) throw unavailable();
                ProbeResult result = anonymous.probe(expected);
                if ((result.original() != 401 && result.original() != 403)
                        || (result.transformed() != 401 && result.transformed() != 403)) throw unavailable();
                if (!delegate.find(entry.key).filter(expected::equals).isPresent()) throw unavailable();
                entry.probed = true;
                event(entry, "anonymous-denied-original-" + result.original() + "-transformed-" + result.transformed());
            }
            return bytes;
        } catch (Exception failure) {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            entry.failed = true; eventQuietly(entry, "read-or-anonymous-check-failed");
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    @Override public synchronized void delete(String key, String assetId) {
        Entry entry = entry(key);
        if (entry.failed || entry.receipt == null || !entry.receipt.assetId().equals(assetId)) throw unavailable();
        try {
            delegate.delete(key, assetId);
            if (delegate.find(key).isPresent()) throw unavailable();
            entry.deleted = true; event(entry, "http-delete-confirmed");
        } catch (RuntimeException failure) { eventQuietly(entry, "http-delete-unconfirmed"); throw unavailable(); }
    }

    synchronized void verifyRecord(String key, long bytes, String sha) {
        Entry entry = entries.get(key);
        require(entry != null && entry.receipt != null && entry.receipt.bytes() == bytes
                && entry.expected.sha256().equals(sha) && entry.inducedAckLoss && entry.probed
                && entry.findCalls > 0 && entry.readCalls > 0 && entry.deleted && !entry.failed);
    }
    synchronized void verifyComplete() {
        require(entries.size() == MAX_ASSETS);
        for (Entry entry : entries.values()) {
            verifyRecord(entry.key, entry.expected.bytes(), entry.expected.sha256());
            try { require(delegate.find(entry.key).isEmpty()); }
            catch (RuntimeException failure) { throw new AssertionError("Synthetic provider final absence was not confirmed; journal=" + journal); }
        }
        event(null, "browser-four-assets-passed-and-deleted");
    }

    /** Stops new application operations first. A missing key after an uncertain upload is not proof of cleanup. */
    synchronized void cleanupOwned() {
        stopping = true;
        if (cleanupAttempted) return;
        cleanupAttempted = true;
        boolean pending = false;
        for (Entry entry : entries.values()) {
            if (!entry.attempted || entry.collision) continue;
            try {
                if (entry.receipt == null) {
                    Optional<StoredAsset> found = delegate.find(entry.key);
                    if (found.isEmpty()) {
                        pending = true; event(entry, "uncertain-upload-key-currently-absent-cleanup-pending"); continue;
                    }
                    verifyReceipt(entry, found.get());
                    byte[] recovered = delegate.read(found.get(), (int) entry.expected.bytes());
                    try { verifyBytes(entry, recovered); } finally { Arrays.fill(recovered, (byte) 0); }
                    entry.receipt = found.get();
                    event(entry, "uncertain-upload-identity-recovered");
                }
                if (!entry.deleted) delegate.delete(entry.key, entry.receipt.assetId());
                if (delegate.find(entry.key).isPresent()) throw unavailable();
                entry.deleted = true;
                event(entry, "cleanup-confirmed");
            } catch (RuntimeException failure) { pending = true; eventQuietly(entry, "cleanup-pending"); }
        }
        if (pending) throw new AssertionError("Synthetic provider cleanup requires review; journal=" + journal);
        event(null, "cleanup-complete");
    }
    @Override public synchronized void close() {
        if (closed) return;
        try { cleanupOwned(); }
        finally { closed = true; try { closeAdapter.run(); } catch (RuntimeException failure) { throw configuration(); } }
    }
    @Override public String toString() { return "BrowserCloudinaryStorage[isolated-synthetic-run]"; }

    private Entry entry(String key) {
        if (stopping || key == null || !key.matches("ordenfix-private/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw unavailable();
        Entry existing = entries.get(key);
        if (existing != null) { if (existing.collision || existing.failed) throw unavailable(); return existing; }
        if (entries.size() >= MAX_ASSETS) throw unavailable();
        try {
            UUID id = UUID.fromString(key.substring("ordenfix-private/".length()));
            Expected expected = Objects.requireNonNull(ownership.apply(id));
            Entry entry = new Entry(key, expected);
            entries.put(key, entry); event(entry, "owned-database-row-admitted"); return entry;
        } catch (RuntimeException failure) { throw unavailable(); }
    }
    private static void verifyReceipt(Entry entry, StoredAsset receipt) {
        if (receipt == null || !entry.key.equals(receipt.objectKey()) || !entry.expected.mimeType().equals(receipt.mimeType())
                || receipt.bytes() != entry.expected.bytes() || receipt.assetId() == null || !receipt.assetId().matches("[A-Za-z0-9_-]{1,128}")
                || receipt.version() == null || !receipt.version().matches("[0-9]{1,32}")) throw unavailable();
    }
    private static void verifyBytes(Entry entry, byte[] bytes) {
        try {
            if (bytes == null || bytes.length != entry.expected.bytes() || !entry.expected.sha256().equals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) throw unavailable();
        } catch (Exception failure) { throw unavailable(); }
    }
    private void event(Entry entry, String stage) {
        String line = Instant.now() + " stage=" + stage + (entry == null ? "" : " key=" + entry.key
                + (entry.receipt == null ? "" : " assetId=" + entry.receipt.assetId() + " version=" + entry.receipt.version())) + "\n";
        try (FileChannel output = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        } catch (Exception failure) { throw unavailable(); }
    }
    private void eventQuietly(Entry entry, String stage) { try { event(entry, stage); } catch (RuntimeException ignored) { } }
    private static ProbeResult probe(String cloud, StoredAsset asset) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).connectTimeout(Duration.ofSeconds(5)).build()) {
            return new ProbeResult(probe(client, cloud, asset, ""), probe(client, cloud, asset, "c_scale,w_32/"));
        }
    }
    private static int probe(HttpClient client, String cloud, StoredAsset asset, String transformation) throws Exception {
        URI uri = URI.create("https://res.cloudinary.com/" + cloud + "/image/authenticated/" + transformation + "v" + asset.version() + "/" + asset.objectKey() + ".png");
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Synthetic provider browser evidence is incomplete."); }
    private static IllegalStateException configuration() { return new IllegalStateException("Invalid isolated synthetic photo provider configuration."); }
    private static PrivatePhotoStorageException unavailable() { return new PrivatePhotoStorageException(PrivatePhotoStorageException.Reason.UNAVAILABLE); }
    private static final class Entry {
        final String key; final Expected expected;
        StoredAsset receipt;
        boolean prechecked, attempted, collision, failed, inducedAckLoss, probed, deleted;
        int findCalls, readCalls;
        Entry(String key, Expected expected) { this.key = key; this.expected = expected; }
    }
}
