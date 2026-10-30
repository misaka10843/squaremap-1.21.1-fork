package xyz.jpenilla.squaremap.common.httpd;

import io.undertow.Undertow;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.inject.annotation.DataDirectory;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StaticAssetsTest {
    @TempDir Path root;

    @Test
    void directHttpCompressesVersionedAssetsAndRevalidatesUnversionedFiles() throws Exception {
        final int oldPort = Config.HTTPD_PORT;
        final String oldBind = Config.HTTPD_BIND;
        final String oldWeb = Config.WEB_DIR;
        Config.HTTPD_PORT = 0;
        Config.HTTPD_BIND = "127.0.0.1";
        Config.WEB_DIR = "web";
        final var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() { bind(Path.class).annotatedWith(DataDirectory.class).toInstance(root); }
        });
        final var directories = injector.getInstance(DirectoryProvider.class);
        directories.init();
        Files.createDirectories(directories.webDirectory());
        final Path script = directories.webDirectory().resolve("main.js");
        final String text = "const text = '地图';\n".repeat(1000);
        Files.writeString(script, text);
        final Undertow server = IntegratedServer.buildUndertow(directories, injector.getInstance(JsonCache.class), new EventStream());
        server.start();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            final int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
            final URI uri = URI.create("http://127.0.0.1:" + port + "/main.js?v=0123456789abcdef");
            final var compressed = client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, compressed.statusCode());
            assertEquals("gzip", compressed.headers().firstValue("content-encoding").orElseThrow());
            assertTrue(compressed.headers().firstValue("cache-control").orElseThrow().contains("immutable"));
            assertTrue(compressed.body().length < text.getBytes(StandardCharsets.UTF_8).length / 4);
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
                assertEquals(text, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
            }
            final String tag = compressed.headers().firstValue("etag").orElseThrow();
            assertEquals(304, client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").header("If-None-Match", tag).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            final var identity = client.send(HttpRequest.newBuilder(uri.resolve("/main.js")).header("Accept-Encoding", "gzip;q=0, *;q=1").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(identity.headers().firstValue("content-encoding").isEmpty());
            assertEquals("no-cache", identity.headers().firstValue("cache-control").orElseThrow());
            assertNotEquals(tag, identity.headers().firstValue("etag").orElseThrow());
            Files.writeString(script, "changed");
            assertEquals("changed", client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body());
            final var head = client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(0, head.body().length);
            final Path tile = directories.webDirectory().resolve("tiles/a/0/0_0.png");
            Files.createDirectories(tile.getParent());
            Files.write(tile, new byte[]{1,2,3});
            final var cachedTile = client.send(HttpRequest.newBuilder(uri.resolve("/tiles/a/0/0_0.png?v=e.1")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("public, max-age=300", cachedTile.headers().firstValue("cache-control").orElseThrow());
            assertArrayEquals(new byte[]{1,2,3}, cachedTile.body());
            assertTrue(cachedTile.headers().firstValue("content-encoding").isEmpty());
            final var missing = client.send(HttpRequest.newBuilder(uri.resolve("/tiles/a/0/1_1.webp?v=e.1")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, missing.statusCode());
            assertEquals(0, missing.body().length);
            assertEquals("public, max-age=30", missing.headers().firstValue("cache-control").orElseThrow());
        } finally {
            server.stop(); Config.HTTPD_PORT = oldPort; Config.HTTPD_BIND = oldBind; Config.WEB_DIR = oldWeb;
        }
    }
}
