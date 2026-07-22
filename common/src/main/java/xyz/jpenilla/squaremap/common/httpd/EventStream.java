package xyz.jpenilla.squaremap.common.httpd;

import com.google.inject.Singleton;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Small broadcast-only SSE hub. Slow or closed clients never queue work on the server thread. */
@Singleton
public final class EventStream {
    public static final String PATH = "/api/events";
    private final Set<ServerSentEventConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final ServerSentEventHandler handler = new ServerSentEventHandler((connection, ignored) -> {
        this.connections.add(connection);
        connection.setKeepAliveTime(15_000L);
        connection.addCloseTask(closed -> this.connections.remove(closed));
        connection.sendRetry(3_000L);
        connection.send("{\"connected\":true}", "ready", Long.toString(this.sequence.incrementAndGet()), null);
    });

    public ServerSentEventHandler handler() {
        return this.handler;
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
        if (this.connections.isEmpty()) {
            return;
        }
        final String id = Long.toString(this.sequence.incrementAndGet());
        for (final ServerSentEventConnection connection : this.connections) {
            if (topic != null) {
                if (!subscribed(connection, topic)) {
                    continue;
                }
            }
            if (connection.isOpen()) {
                connection.send(json, event, id, new ServerSentEventConnection.EventCallback() {
                    @Override
                    public void done(final ServerSentEventConnection ignored, final String data, final String eventName, final String eventId) {
                    }

                    @Override
                    public void failed(final ServerSentEventConnection closed, final String data, final String eventName, final String eventId, final java.io.IOException failure) {
                        EventStream.this.connections.remove(closed);
                        try {
                            closed.close();
                        } catch (final java.io.IOException ignored) {
                        }
                    }
                });
            } else {
                this.connections.remove(connection);
            }
        }
    }

    public void close() {
        for (final ServerSentEventConnection connection : this.connections) {
            connection.shutdown();
        }
        this.connections.clear();
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
