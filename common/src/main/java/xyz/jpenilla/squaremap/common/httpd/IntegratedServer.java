package xyz.jpenilla.squaremap.common.httpd;

import io.undertow.Undertow;
import io.undertow.UndertowLogger;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.util.ETag;
import io.undertow.util.Headers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.config.Messages;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;

public final class IntegratedServer {
    private static Undertow SERVER;
    private static EventStream EVENTS;

    private IntegratedServer() {
    }

    public static void startServer(final DirectoryProvider directoryProvider, final JsonCache jsonCache, final EventStream eventStream) {
        EVENTS = eventStream;
        eventStream.start();
        try {
            SERVER = buildUndertow(directoryProvider, jsonCache, eventStream);
            SERVER.start();

            Logging.info(Messages.LOG_INTERNAL_WEB_STARTED, "bind", Config.HTTPD_BIND, "port", Config.HTTPD_PORT);
        } catch (Exception e) {
            SERVER = null;
            Logging.logger().error(Messages.LOG_INTERNAL_WEB_START_ERROR, e);
        }
    }

    static Undertow buildUndertow(final DirectoryProvider directoryProvider, final JsonCache cache, final EventStream events) {
        final ResourceHandler resourceHandler = createResourceHandler(directoryProvider);
        final StaticAssets assets = new StaticAssets(directoryProvider.webDirectory());
        return Undertow.builder()
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .addHttpListener(Config.HTTPD_PORT, Config.HTTPD_BIND)
            .setHandler(exchange -> {
                if (exchange.getRelativePath().equals(EventStream.PATH)) {
                    if (exchange.isInIoThread()) exchange.dispatch(events.handler());
                    else events.handler().handleRequest(exchange);
                    return;
                }
                if (exchange.isInIoThread()) {
                    exchange.dispatch(() -> {
                        try {
                            handleResources(exchange, resourceHandler, assets, cache);
                        } catch (final Exception exception) {
                            exchange.setStatusCode(500); exchange.endExchange();
                            Logging.logger().warn("Failed to serve web resource", exception);
                        }
                    });
                    return;
                }

                handleResources(exchange, resourceHandler, assets, cache);
            })
            .build();
    }

    private static void handleResources(final io.undertow.server.HttpServerExchange exchange,
        final ResourceHandler resourceHandler, final StaticAssets assets, final JsonCache cache) throws Exception {
        final String path = exchange.getRelativePath();
        if (path.startsWith("/tiles")) {
            final boolean versionedTile = (path.endsWith(".png") || path.endsWith(".webp"))
                && exchange.getQueryParameters().containsKey("v");
            exchange.getResponseHeaders().put(Headers.CACHE_CONTROL,
                versionedTile ? "public, max-age=300" : "no-cache");
        }
        if (cache.handle(exchange)) return;
        if (exchange.getRequestHeaders().contains(Headers.IF_NONE_MATCH)) exchange.getRequestHeaders().remove(Headers.IF_MODIFIED_SINCE);
        if (!assets.handle(exchange)) resourceHandler.handleRequest(exchange);
    }

    private static ResourceHandler createResourceHandler(final DirectoryProvider directoryProvider) {
        final ResourceManager resourceManager = PathResourceManager.builder()
            .setBase(Paths.get(directoryProvider.webDirectory().toFile().getAbsolutePath()))
            .setETagFunction((path) -> {
                final BasicFileAttributes attr;
                try {
                    attr = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (final IOException e) {
                    Logging.logger().warn("Failed to read file attributes for {}", path, e);
                    return null;
                }
                final long time = attr.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
                return new ETag(false, Long.toString(time, 36) + '-' + Long.toString(attr.size(), 36));
            })
            .build();

        return new ResourceHandler(
            resourceManager,
            exchange -> {
                final String url = exchange.getRelativePath();
                if (url.startsWith("/tiles") && (url.endsWith(".png") || url.endsWith(".webp"))) {
                    exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=30");
                    exchange.setStatusCode(200);
                    exchange.endExchange();
                    return;
                }
                exchange.setStatusCode(404);
                if (UndertowLogger.PREDICATE_LOGGER.isDebugEnabled()) {
                    UndertowLogger.PREDICATE_LOGGER.debugf("Response code set to [%s] for %s.", 404, exchange);
                }
                exchange.endExchange();
            }
        );
    }

    public static void stopServer() {
        if (SERVER == null) {
            Logging.logger().warn(Messages.LOG_INTERNAL_WEB_STOP_ERROR);
            return;
        }

        SERVER.stop();
        if (EVENTS != null) {
            EVENTS.close();
        }
        SERVER = null;
        Logging.logger().info(Messages.LOG_INTERNAL_WEB_STOPPED);
    }
}
