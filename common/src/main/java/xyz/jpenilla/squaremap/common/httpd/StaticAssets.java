package xyz.jpenilla.squaremap.common.httpd;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/** Bounded, representation-aware cache for text assets served directly by Undertow. */
final class StaticAssets {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final Path root;
    private final Map<Path, Asset> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long retainedBytes;

    StaticAssets(final Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    boolean handle(final HttpServerExchange exchange) throws IOException {
        if ((!exchange.getRequestMethod().equals(Methods.GET) && !exchange.getRequestMethod().equals(Methods.HEAD))
            || exchange.getRequestHeaders().contains(Headers.RANGE)) return false;
        final String name = exchange.getRelativePath().equals("/") ? "/index.html" : exchange.getRelativePath();
        final String type;
        if (name.endsWith(".js")) type = "text/javascript; charset=utf-8";
        else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (name.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (name.endsWith(".svg")) type = "image/svg+xml";
        else if (name.endsWith(".json")) type = "application/json; charset=utf-8";
        else return false;
        final Path file = this.root.resolve(name.substring(1)).normalize();
        if (!file.startsWith(this.root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(this.root.toRealPath())) return false;
        final var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        if (attributes.size() > MAX_BYTES / 2) return false;
        final String stamp = Long.toString(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), 36) + '-' + attributes.size();
        final Asset asset;
        synchronized (this.entries) {
            final Asset previous = this.entries.get(file);
            if (previous != null && previous.stamp.equals(stamp)) asset = previous;
            else {
                if (previous != null) this.retainedBytes -= previous.size();
                final byte[] plain = Files.readAllBytes(file);
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(plain); }
                asset = new Asset(stamp, plain, buffer.toByteArray());
                this.entries.put(file, asset);
                this.retainedBytes += asset.size();
                final var iterator = this.entries.values().iterator();
                while (this.retainedBytes > MAX_BYTES && iterator.hasNext()) {
                    this.retainedBytes -= iterator.next().size();
                    iterator.remove();
                }
            }
        }
        final boolean gzip = asset.compressed.length < asset.plain.length && JsonCache.allowsGzip(exchange.getRequestHeaders().getFirst(Headers.ACCEPT_ENCODING));
        final String etag = '"' + stamp + (gzip ? "-gzip\"" : "-identity\"");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, type);
        exchange.getResponseHeaders().put(Headers.VARY, "Accept-Encoding");
        exchange.getResponseHeaders().put(Headers.ETAG, etag);
        // Build-generated versions are changed whenever any frontend source changes.
        final var versions = exchange.getQueryParameters().get("v");
        final boolean versioned = (name.endsWith(".js") || name.endsWith(".css")) && versions != null
            && versions.size() == 1 && versions.getFirst().matches("[0-9a-f]{16}");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, versioned ? "public, max-age=31536000, immutable" : "no-cache");
        if (gzip) exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
        if (JsonCache.matches(exchange.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH), etag)) {
            exchange.setStatusCode(304); exchange.endExchange(); return true;
        }
        final byte[] body = gzip ? asset.compressed : asset.plain;
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
        if (exchange.getRequestMethod().equals(Methods.HEAD)) exchange.endExchange();
        else exchange.getResponseSender().send(ByteBuffer.wrap(body));
        return true;
    }

    private record Asset(String stamp, byte[] plain, byte[] compressed) {
        long size() { return (long) this.plain.length + this.compressed.length; }
    }
}
