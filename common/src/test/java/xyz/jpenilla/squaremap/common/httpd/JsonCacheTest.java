package xyz.jpenilla.squaremap.common.httpd;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.inject.annotation.DataDirectory;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class JsonCacheTest {
    @TempDir Path directory;
    private DirectoryProvider directories;
    private JsonCache cache;
    private Undertow server;
    private URI uri;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void start() {
        Config.HTTPD_ENABLED = true;
        Config.FLUSH_JSON_IMMEDIATELY = false;
        Config.WEB_DIR = "web";
        final var injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Path.class).annotatedWith(DataDirectory.class).toInstance(directory);
            }
        });
        this.directories = injector.getInstance(DirectoryProvider.class);
        this.directories.init();
        this.cache = injector.getInstance(JsonCache.class);
        this.server = Undertow.builder().setIoThreads(1).setWorkerThreads(2).addHttpListener(0, "127.0.0.1")
            .setHandler(exchange -> {
                if (!this.cache.handle(exchange)) {
                    exchange.setStatusCode(404);
                    exchange.endExchange();
                }
            }).build();
        this.server.start();
        final int port = ((InetSocketAddress) this.server.getListenerInfo().getFirst().getAddress()).getPort();
        this.uri = URI.create("http://127.0.0.1:" + port + "/tiles/test.json");
    }

    @AfterEach
    void stop() {
        if (this.server != null) this.server.stop();
        Config.HTTPD_ENABLED = true;
    }

    @Test
    void gzipAndIdentityHaveDistinctValidatorsAndCorrectBodies() throws Exception {
        final String json = "{\"text\":\"" + "地图".repeat(800) + "\"}";
        this.cache.put(this.directories.webDirectory().resolve("tiles/test.json"), json);
        final var identity = this.get("identity", null);
        final var gzip = this.get("gzip", null);
        assertEquals(200, identity.statusCode());
        assertEquals(json, new String(identity.body(), StandardCharsets.UTF_8));
        assertEquals("no-cache", identity.headers().firstValue("cache-control").orElseThrow());
        final String identityTag = identity.headers().firstValue("etag").orElseThrow();
        final String gzipTag = gzip.headers().firstValue("etag").orElseThrow();
        assertNotEquals(identityTag, gzipTag);
        assertEquals("gzip", gzip.headers().firstValue("content-encoding").orElseThrow());
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(gzip.body()))) {
            assertEquals(json, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(304, this.get("gzip", gzipTag).statusCode());
        assertEquals(304, this.get("identity", "\"other\", W/" + identityTag).statusCode());
        assertEquals(200, this.get("identity", gzipTag).statusCode());
        assertTrue(this.get("gzip;q=0, *;q=1", null).headers().firstValue("content-encoding").isEmpty());
    }

    @Test
    void resetNeverRepeatsAnEarlierValidator() throws Exception {
        this.cache.put("/tiles/test.json", "{}");
        final String first = this.get("identity", null).headers().firstValue("etag").orElseThrow();
        this.cache.put("/tiles/test.json", "{}");
        assertEquals(304, this.get("identity", first).statusCode());
        this.cache.clearPrefix("/tiles/");
        this.cache.put("/tiles/test.json", "{}");
        final var rewritten = this.get("identity", first);
        assertEquals(200, rewritten.statusCode());
        assertNotEquals(first, rewritten.headers().firstValue("etag").orElseThrow());
    }

    @Test
    void externalServerWritesFilesAndPathsStayInsideWebRoot() throws Exception {
        Config.HTTPD_ENABLED = false;
        final Path file = this.directories.webDirectory().resolve("tiles/test.json");
        this.cache.put(file, "{\"ok\":true}");
        assertEquals("{\"ok\":true}", Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> this.cache.put(this.directory.resolve("outside.json"), "{}"));
    }

    @Test
    void cursorResponsesKeepStableValidatorsAndPreserveRecoveryMetadata() throws Exception {
        this.cache.put("/tiles/changes.json", "{\"epoch\":\"e\",\"seq\":5,\"dropped\":2,\"changes\":[[3,1,0,0],[5,1,2,2]]}");
        this.uri = this.uri.resolve("/tiles/changes.json?since=3");
        final var first = this.get("identity", null);
        final var json = com.google.gson.JsonParser.parseString(new String(first.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("changes").size());
        assertEquals(2, json.get("dropped").getAsInt());
        final String tag = first.headers().firstValue("etag").orElseThrow();
        assertFalse(tag.substring(1, tag.length() - 1).contains("\""));
        assertEquals(304, this.get("identity", tag).statusCode());
        this.uri = this.uri.resolve("/tiles/changes.json?since=5");
        assertEquals(200, this.get("identity", tag).statusCode());
    }

    @Test
    void worldNetworkResponsesKeepPortalLinksAndOnlyMatchingGeometry() throws Exception {
        this.cache.put("/tiles/create/network.json", "{\"tracks\":[{\"world\":\"a\"},{\"world\":\"b\"}],\"stations\":[],\"signals\":[],\"signal_blocks\":[{\"id\":\"x\",\"segments\":[{\"world\":\"a\"},{\"world\":\"b\"}]}],\"portals\":[{\"from\":{\"world\":\"a\"},\"to\":{\"world\":\"b\"}}]}");
        this.uri = this.uri.resolve("/tiles/create/network.json?world=a");
        final var first = this.get("identity", null);
        final var json = com.google.gson.JsonParser.parseString(new String(first.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("tracks").size());
        assertEquals(1, json.getAsJsonArray("signal_blocks").get(0).getAsJsonObject().getAsJsonArray("segments").size());
        assertEquals(1, json.getAsJsonArray("portals").size());
        assertEquals(304, this.get("identity", first.headers().firstValue("etag").orElseThrow()).statusCode());
    }

    private HttpResponse<byte[]> get(final String encoding, final String etag) throws Exception {
        final var request = HttpRequest.newBuilder(this.uri).timeout(java.time.Duration.ofSeconds(5)).header("Accept-Encoding", encoding);
        if (etag != null) request.header("If-None-Match", etag);
        return this.client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
