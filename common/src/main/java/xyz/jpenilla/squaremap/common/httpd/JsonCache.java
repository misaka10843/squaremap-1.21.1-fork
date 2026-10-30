package xyz.jpenilla.squaremap.common.httpd;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.util.FileUtil;

@DefaultQualifier(NonNull.class)
@Singleton
public final class JsonCache {
    private static final int GZIP_MIN_BYTES = 1024;
    private static final String ETAG_EPOCH = UUID.randomUUID().toString();
    private static final AtomicLong NEXT_ETAG = new AtomicLong();

    private final DirectoryProvider directoryProvider;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccessNanos = new ConcurrentHashMap<>();

    @Inject
    private JsonCache(final DirectoryProvider directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    boolean handle(final HttpServerExchange exchange) {
        final String relativePath = exchange.getRelativePath();
        final @Nullable Entry original = this.cache.get(relativePath);
        if (original == null) {
            return false;
        }
        Entry cached = original;
        final var worlds = exchange.getQueryParameters().get("world");
        if (relativePath.endsWith("/create/network.json") && worlds != null && worlds.size() == 1) {
            final String world = worlds.getFirst();
            if (world.matches("[A-Za-z0-9_.-]{1,128}")) {
                Entry variant = original.variants.get(world);
                if (variant == null) {
                    final var object = com.google.gson.JsonParser.parseString(original.json).getAsJsonObject();
                    for (final String field : java.util.List.of("tracks", "stations", "signals")) {
                        final var filtered = new com.google.gson.JsonArray();
                        final var source = object.getAsJsonArray(field);
                        if (source != null) for (final var row : source) {
                            if (world.equals(row.getAsJsonObject().get("world").getAsString())) filtered.add(row);
                        }
                        object.add(field, filtered);
                    }
                    final var blocks = new com.google.gson.JsonArray();
                    if (object.has("signal_blocks")) for (final var block : object.getAsJsonArray("signal_blocks")) {
                        final var segments = new com.google.gson.JsonArray();
                        for (final var segment : block.getAsJsonObject().getAsJsonArray("segments")) {
                            if (world.equals(segment.getAsJsonObject().get("world").getAsString())) segments.add(segment);
                        }
                        if (!segments.isEmpty()) {
                            final var copy = block.getAsJsonObject().deepCopy();
                            copy.add("segments", segments); blocks.add(copy);
                        }
                    }
                    object.add("signal_blocks", blocks);
                    final var portals = new com.google.gson.JsonArray();
                    if (object.has("portals")) for (final var portal : object.getAsJsonArray("portals")) {
                        final var pair = portal.getAsJsonObject();
                        if (java.util.List.of("from", "to").stream().anyMatch(side -> pair.has(side)
                            && world.equals(pair.getAsJsonObject(side).get("world").getAsString()))) portals.add(portal);
                    }
                    object.add("portals", portals);
                    variant = new Entry(object.toString(), true, false,
                        original.etag.substring(1, original.etag.length() - 1) + "-world-" + world);
                    if (original.variants.size() < 16) original.variants.putIfAbsent(world, variant);
                }
                cached = variant;
            }
        }
        final var cursors = exchange.getQueryParameters().get("since");
        if (relativePath.endsWith("/changes.json") && cursors != null && cursors.size() == 1) {
            try {
                final long since = Long.parseLong(cursors.getFirst());
                if (since >= 0) {
                    final var object = com.google.gson.JsonParser.parseString(original.json).getAsJsonObject();
                    final var changes = new com.google.gson.JsonArray();
                    for (final var item : object.getAsJsonArray("changes")) {
                        if (item.getAsJsonArray().get(0).getAsLong() > since) changes.add(item);
                    }
                    object.add("changes", changes);
                    cached = new Entry(object.toString(), true, false, original.etag.substring(1, original.etag.length() - 1) + "-since-" + since);
                }
            } catch (final RuntimeException ignored) {
                // Malformed cursors use the complete compatibility document.
            }
        }

        this.lastAccessNanos.put(relativePath, System.nanoTime());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
        exchange.getResponseHeaders().put(Headers.VARY, Headers.ACCEPT_ENCODING_STRING);

        final boolean gzip = cached.utf8Length >= GZIP_MIN_BYTES && allowsGzip(exchange.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING));
        final String etag = gzip ? cached.gzipEtag : cached.etag;
        exchange.getResponseHeaders().put(Headers.ETAG, etag);
        if (gzip) exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");

        final @Nullable String ifNoneMatch = exchange.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH);
        if (matches(ifNoneMatch, etag)) {
            exchange.setStatusCode(StatusCodes.NOT_MODIFIED);
            exchange.endExchange();
            return true;
        }

        if (gzip) {
            final byte[] compressed = cached.gzip();
            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(compressed.length));
            exchange.getResponseSender().send(ByteBuffer.wrap(compressed));
        } else {
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(cached.utf8Length));
            exchange.getResponseSender().send(ByteBuffer.wrap(cached.utf8));
        }
        return true;
    }

    public void put(final String path, final @Nullable String json) {
        this.put(path, json, false, false);
    }

    public void put(final Path file, final @Nullable String json) {
        final Path root = this.directoryProvider.webDirectory().toAbsolutePath().normalize();
        final Path relative = root.relativize(file.toAbsolutePath().normalize());
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IllegalArgumentException("JSON file is outside web directory: " + file);
        this.put('/' + FileUtil.invariantSeparatorsPathString(relative), json, false, true);
    }

    static boolean matches(final @Nullable String header, final String etag) {
        if (header == null) return false;
        for (final String item : header.split(",")) {
            final String token = item.trim();
            if (token.equals("*") || token.equals(etag) || token.equals("W/" + etag)) return true;
        }
        return false;
    }

    static boolean allowsGzip(final @Nullable String header) {
        if (header == null) return false;
        Boolean wildcard = null;
        for (final String item : header.split(",")) {
            final String[] parts = item.trim().split(";");
            if (!parts[0].equalsIgnoreCase("gzip") && !parts[0].equals("*")) continue;
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                final String parameter = parts[i].trim();
                if (parameter.startsWith("q=")) {
                    try { quality = Double.parseDouble(parameter.substring(2)); }
                    catch (final NumberFormatException ignored) { quality = 0; }
                }
            }
            if (parts[0].equalsIgnoreCase("gzip")) return quality > 0 && quality <= 1;
            wildcard = quality > 0 && quality <= 1;
        }
        return Boolean.TRUE.equals(wildcard);
    }

    /**
     * Cache data for the integrated server without persisting it to the web
     * directory. When the integrated server is disabled, the file is still
     * written because an external web server needs to read it.
     */
    public void putVolatile(final String path, final @Nullable String json) {
        this.put(path, json, true, false);
    }

    private void put(final String path, final @Nullable String json, final boolean volatileEntry, final boolean precompress) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(path);
        }

        if (json == null) {
            this.cache.remove(path);
            this.lastAccessNanos.remove(path);
            return;
        }

        final Entry previous = this.cache.get(path);
        if (previous != null && previous.json.equals(json) && previous.volatileEntry == volatileEntry) {
            return;
        }

        this.cache.put(path, new Entry(json, volatileEntry, precompress));
        if ((!volatileEntry || !Config.HTTPD_ENABLED)
            && (Config.FLUSH_JSON_IMMEDIATELY || !Config.HTTPD_ENABLED)) {
            this.write(path, json);
        }
    }

    public long lastAccessNanos(final String path) {
        return this.lastAccessNanos.getOrDefault(path, Long.MIN_VALUE);
    }

    public boolean accessedWithin(final String path, final long durationNanos) {
        final long lastAccess = this.lastAccessNanos(path);
        return lastAccess != Long.MIN_VALUE && System.nanoTime() - lastAccess <= durationNanos;
    }

    public void flush() {
        this.cache.forEach((path, entry) -> {
            if (!entry.volatileEntry || !Config.HTTPD_ENABLED) {
                this.write(path, entry.json);
            }
        });
    }

    public void clear() {
        this.cache.clear();
        this.lastAccessNanos.clear();
    }

    public void clearPrefix(final String prefix) {
        this.cache.keySet().removeIf(path -> path.startsWith(prefix));
        this.lastAccessNanos.keySet().removeIf(path -> path.startsWith(prefix));
    }

    private void write(final String path, final String data) {
        try {
            final Path file = this.directoryProvider.webDirectory().resolve("." + path);
            Files.createDirectories(file.getParent());
            FileUtil.atomicWrite(file, tmp -> Files.writeString(tmp, data));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Entry {
        private final String json;
        private final boolean volatileEntry;
        private final int utf8Length;
        private final byte[] utf8;
        private final String etag;
        private final String gzipEtag;
        private volatile byte @Nullable [] gzip;
        private final Map<String, Entry> variants = new ConcurrentHashMap<>();

        private Entry(final String json, final boolean volatileEntry, final boolean precompress) {
            this(json, volatileEntry, precompress, ETAG_EPOCH + '-' + NEXT_ETAG.incrementAndGet());
        }

        private Entry(final String json, final boolean volatileEntry, final boolean precompress, final String version) {
            this.json = json;
            this.volatileEntry = volatileEntry;
            this.utf8 = json.getBytes(StandardCharsets.UTF_8);
            this.utf8Length = this.utf8.length;
            this.etag = '"' + version + "-json\"";
            this.gzipEtag = '"' + version + "-gzip\"";
            if (precompress && Config.HTTPD_ENABLED && this.utf8Length >= GZIP_MIN_BYTES) this.gzip();
        }

        private byte[] gzip() {
            byte @Nullable [] result = this.gzip;
            if (result != null) {
                return result;
            }
            synchronized (this) {
                result = this.gzip;
                if (result == null) {
                    try {
                        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(256, this.utf8Length / 3));
                        try (GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
                            gzipOutput.write(this.utf8);
                        }
                        result = output.toByteArray();
                        this.gzip = result;
                    } catch (final IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
            }
            return result;
        }
    }
}
