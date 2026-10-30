package xyz.jpenilla.squaremap.common.httpd;

import com.google.inject.Singleton;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Small broadcast-only SSE hub. Slow or closed clients never queue work on the server thread. */
@Singleton
public final class EventStream {
    private static final com.google.gson.Gson DELTA_JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    public static final String PATH = "/api/events";
    private final Set<ServerSentEventConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Channel> collections = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Map<String, String> compressedMessages = new LinkedHashMap<>(16, 0.75F, true);
    private long compressedCacheBytes;
    private boolean closed;
    private final Map<ServerSentEventConnection, java.util.concurrent.atomic.AtomicInteger> pending = new ConcurrentHashMap<>();
    private final ServerSentEventHandler handler = new ServerSentEventHandler((connection, ignored) -> {
        synchronized (this) {
            if (this.closed) { connection.shutdown(); return; }
            this.connections.add(connection);
            connection.setKeepAliveTime(30_000L);
            this.pending.put(connection, new java.util.concurrent.atomic.AtomicInteger());
            connection.addCloseTask(closed -> { this.connections.remove(closed); this.pending.remove(closed); });
            connection.sendRetry(3_000L);
            connection.send("{\"connected\":true,\"protocol\":2}", "ready", Long.toString(this.sequence.incrementAndGet()), null);
            if (modern(connection)) {
                for (final Channel channel : this.collections.values()) {
                    if (matches(connection, channel.topic, channel.world)) {
                        this.send(connection, channel.topic, DELTA_JSON.toJson(channel.delta.snapshot()));
                    }
                }
            }
            if (subscribed(connection, "base")) this.states.forEach((event, json) -> this.send(connection, event, json));
        }
    });

    public ServerSentEventHandler handler() {
        return this.handler;
    }

    public synchronized void start() {
        this.closed = false;
    }

    public boolean hasViewers() {
        return !this.connections.isEmpty();
    }

    public boolean hasViewers(final String topic) {
        for (final ServerSentEventConnection connection : this.connections) {
            if (subscribed(connection, topic)) {
                return true;
            }
        }
        return false;
    }

    public void publish(final String event, final String json) {
        this.publish(event, json, null);
    }

    public void publish(final String event, final String json, final String topic) {
        this.publish(event, json, topic, "");
    }

    public synchronized void publish(final String event, final String json, final String topic, final String world) {
        if (this.closed) return;
        if (this.connections.isEmpty()) {
            return;
        }
        for (final ServerSentEventConnection connection : this.connections) {
            if (!matches(connection, topic, world)) continue;
            this.send(connection, event, json);
        }
    }

    /** Retained state is sent once to new base subscribers; identical states are suppressed. */
    public synchronized void publishState(final String event, final String json) {
        if (this.closed) return;
        if (json.equals(this.states.put(event, json))) return;
        this.publish(event, json, "base");
    }

    public synchronized void publishCollection(final String topic, final String world, final String key,
        final List<? extends Map<String, Object>> rows, final Map<String, Object> metadata) {
        if (this.closed) return;
        final boolean created = !this.collections.containsKey(topic + '\n' + world);
        final Channel channel = this.collections.computeIfAbsent(topic + '\n' + world,
            ignored -> new Channel(topic, world, new CollectionDelta(key)));
        final Map<String, Object> patch = channel.delta.update(rows, metadata);
        if (patch.isEmpty() && !created) return;
        final String json = DELTA_JSON.toJson(created ? channel.delta.snapshot() : patch);
        for (final ServerSentEventConnection connection : this.connections) {
            if (modern(connection) && matches(connection, topic, world)) this.send(connection, topic, json);
        }
    }

    private void send(final ServerSentEventConnection connection, final String event, final String json) {
        if (!connection.isOpen()) return;
        final var count = this.pending.get(connection);
        if (count == null) return;
        if (count.incrementAndGet() > 32) {
            count.decrementAndGet();
            this.connections.remove(connection);
            this.pending.remove(connection);
            connection.shutdown(); return;
        }
        final String data = modern(connection) && parameter(connection, "compression").equals("gzip") ? this.compressed(json) : json;
        connection.send(data, event, Long.toString(this.sequence.incrementAndGet()), new ServerSentEventConnection.EventCallback() {
            @Override public void done(final ServerSentEventConnection ignored, final String body, final String name, final String id) { count.decrementAndGet(); }
            @Override public void failed(final ServerSentEventConnection closed, final String body, final String name, final String id, final java.io.IOException failure) {
                count.decrementAndGet();
                try { closed.close(); } catch (final java.io.IOException ignored) {}
            }
        });
    }

    private String compressed(final String json) {
        if (json.length() < 1024) return json;
        final String cached = this.compressedMessages.get(json);
        if (cached != null) return cached;
        try {
            final var buffer = new java.io.ByteArrayOutputStream();
            final byte[] plain = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (final var gzip = new java.util.zip.GZIPOutputStream(buffer)) { gzip.write(plain); }
            final String encoded = "{\"gzip\":\"" + java.util.Base64.getEncoder().encodeToString(buffer.toByteArray()) + "\"}";
            final String result = encoded.length() < plain.length * 0.85 ? encoded : json;
            this.compressedMessages.put(json, result);
            this.compressedCacheBytes += (long) (json.length() + result.length()) * 2;
            final var iterator = this.compressedMessages.entrySet().iterator();
            while (this.compressedCacheBytes > 4 * 1024 * 1024 && iterator.hasNext()) {
                final var entry = iterator.next();
                this.compressedCacheBytes -= (long) (entry.getKey().length() + entry.getValue().length()) * 2;
                iterator.remove();
            }
            return result;
        } catch (final java.io.IOException exception) { return json; }
    }

    private static boolean modern(final ServerSentEventConnection connection) {
        return "2".equals(parameter(connection, "protocol"));
    }

    private static String parameter(final ServerSentEventConnection connection, final String name) {
        final var values = connection.getQueryParameters().get(name);
        return values == null || values.isEmpty() ? "" : values.getFirst();
    }

    private static boolean matches(final ServerSentEventConnection connection, final String topic, final String world) {
        return (topic == null || subscribed(connection, topic))
            && (world.isEmpty() || world.equals(parameter(connection, "world")) || (!modern(connection) && parameter(connection, "world").isEmpty()));
    }

    private record Channel(String topic, String world, CollectionDelta delta) {}

    public synchronized void close() {
        this.closed = true;
        for (final ServerSentEventConnection connection : this.connections) {
            connection.shutdown();
        }
        this.connections.clear();
        this.collections.clear();
        this.states.clear();
        this.pending.clear();
        this.compressedMessages.clear();
        this.compressedCacheBytes = 0;
    }

    private static boolean subscribed(final ServerSentEventConnection connection, final String topic) {
        final java.util.Deque<String> values = connection.getQueryParameters().get("topics");
        if (values == null) {
            return false;
        }
        for (final String value : values) {
            if (java.util.Arrays.asList(value.split(",")).contains(topic)) {
                return true;
            }
        }
        return false;
    }
}
