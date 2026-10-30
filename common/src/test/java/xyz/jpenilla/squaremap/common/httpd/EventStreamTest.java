package xyz.jpenilla.squaremap.common.httpd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.undertow.Undertow;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class EventStreamTest {
    @Test
    void subscriptionsSeedSnapshotsFilterWorldsAndCompressLargePackets() throws Exception {
        final EventStream events = new EventStream();
        events.publishCollection("create-trains", "a", "id", List.of(Map.of("id", "one", "name", "long name ".repeat(500), "x", 1)), Map.of());
        events.publishCollection("create-trains", "b", "id", List.of(Map.of("id", "other")), Map.of());
        final Undertow server = Undertow.builder().setIoThreads(1).setWorkerThreads(2).addHttpListener(0, "127.0.0.1").setHandler(events.handler()).build();
        server.start();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            final int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
            final URI uri = URI.create("http://127.0.0.1:" + port + "/api/events?protocol=2&topics=create-trains&world=a&compression=gzip");
            final var response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertEquals(2, read(reader).get("protocol").getAsInt());
                final JsonObject encoded = read(reader);
                assertTrue(encoded.has("gzip"));
                final JsonObject snapshot;
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded.get("gzip").getAsString())))) {
                    snapshot = JsonParser.parseString(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                assertTrue(snapshot.get("reset").getAsBoolean());
                assertEquals(1, snapshot.getAsJsonArray("items").size());
                events.publishCollection("create-trains", "b", "id", List.of(), Map.of());
                events.publishCollection("create-signals", "a", "id", List.of(Map.of("id", "signal")), Map.of());
                events.publishCollection("create-trains", "a", "id", List.of(), Map.of());
                final JsonObject delta = read(reader);
                assertEquals(snapshot.get("seq"), delta.get("base"));
                assertEquals("one", delta.getAsJsonArray("remove").get(0).getAsString());
            }
        } finally { events.close(); server.stop(); }
    }

    private static JsonObject read(final BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) return JsonParser.parseString(line.substring(5).trim()).getAsJsonObject();
        }
        throw new AssertionError("SSE connection closed before data");
    }
}
