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
import java.util.Map;
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

    private final DirectoryProvider directoryProvider;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccessNanos = new ConcurrentHashMap<>();

    @Inject
    private JsonCache(final DirectoryProvider directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    boolean handle(final HttpServerExchange exchange) {
        final String relativePath = exchange.getRelativePath();
        final @Nullable Entry cached = this.cache.get(relativePath);
        if (cached == null) {
            return false;
        }

        this.lastAccessNanos.put(relativePath, System.nanoTime());
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "max-age=0, must-revalidate");
        exchange.getResponseHeaders().put(Headers.ETAG, cached.etag);
        exchange.getResponseHeaders().put(Headers.VARY, Headers.ACCEPT_ENCODING_STRING);

        final @Nullable String ifNoneMatch = exchange.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH);
        if (cached.etag.equals(ifNoneMatch)) {
            exchange.setStatusCode(StatusCodes.NOT_MODIFIED);
            exchange.endExchange();
            return true;
        }

        final @Nullable String acceptEncoding = exchange.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING);
        if (cached.utf8Length >= GZIP_MIN_BYTES && acceptEncoding != null && acceptEncoding.contains("gzip")) {
            final byte[] compressed = cached.gzip();
            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(compressed.length));
            exchange.getResponseSender().send(ByteBuffer.wrap(compressed));
        } else {
            exchange.getResponseSender().send(cached.json);
        }
        return true;
    }

    public void put(final String path, final @Nullable String json) {
        this.put(path, json, false);
    }

    /**
     * Cache data for the integrated server without persisting it to the web
     * directory. When the integrated server is disabled, the file is still
     * written because an external web server needs to read it.
     */
    public void putVolatile(final String path, final @Nullable String json) {
        this.put(path, json, true);
    }

    private void put(final String path, final @Nullable String json, final boolean volatileEntry) {
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

        this.cache.put(path, new Entry(json, volatileEntry));
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

    private void write(final String path, final String data) {
        try {
            FileUtil.atomicWrite(this.directoryProvider.webDirectory().resolve("." + path), tmp -> Files.writeString(tmp, data));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Entry {
        private final String json;
        private final boolean volatileEntry;
        private final int utf8Length;
        private final String etag;
        private volatile byte @Nullable [] gzip;

        private Entry(final String json, final boolean volatileEntry) {
            this.json = json;
            this.volatileEntry = volatileEntry;
            this.utf8Length = json.getBytes(StandardCharsets.UTF_8).length;
            this.etag = "W/\"" + Integer.toUnsignedString(json.hashCode(), 16) + '-' + this.utf8Length + "\"";
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
                            gzipOutput.write(this.json.getBytes(StandardCharsets.UTF_8));
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
