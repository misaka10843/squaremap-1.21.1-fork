package xyz.jpenilla.squaremap.common.player;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import javax.imageio.ImageIO;
import net.minecraft.server.level.ServerPlayer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.util.Util;

/**
 * Exports player heads into squaremap's own web directory.
 *
 * <p>The browser only requests a same-origin URL such as
 * {@code avatars/<uuid>.png}. This avoids requiring visitors to reach
 * mc-heads.net, Crafatar, or another third-party avatar service.</p>
 */
@DefaultQualifier(NonNull.class)
@Singleton
public final class PlayerHeadCache {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final DirectoryProvider directoryProvider;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> retryAfter = new ConcurrentHashMap<>();

    @Inject
    private PlayerHeadCache(final DirectoryProvider directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    public void ensure(final ServerPlayer player) {
        if (!Config.PLAYER_HEAD_CACHE_ENABLED) {
            return;
        }

        final UUID uuid = player.getUUID();
        final long now = System.currentTimeMillis();
        final Long retryAt = this.retryAfter.get(uuid);
        if (retryAt != null && retryAt > now) {
            return;
        }
        this.retryAfter.remove(uuid);

        final Path target = this.target(uuid);
        if (Files.isRegularFile(target) || !this.inFlight.add(uuid)) {
            return;
        }

        final @Nullable String skinUrl = skinUrl(player);
        if (skinUrl == null) {
            this.inFlight.remove(uuid);
            this.retryAfter.put(uuid, now + Duration.ofMinutes(1).toMillis());
            return;
        }

        ForkJoinPool.commonPool().execute(() -> {
            try {
                this.downloadAndRender(skinUrl, target);
                this.retryAfter.remove(uuid);
            } catch (final Exception ex) {
                Logging.logger().debug("Failed to cache player head for {}", player.getGameProfile().getName(), ex);
                this.retryAfter.put(uuid, System.currentTimeMillis() + Duration.ofMinutes(5).toMillis());
            } finally {
                this.inFlight.remove(uuid);
            }
        });
    }

    private Path target(final UUID uuid) {
        return this.directoryProvider.webDirectory()
            .resolve("avatars")
            .resolve(uuid.toString().replace("-", "") + ".png");
    }

    private void downloadAndRender(final String skinUrl, final Path target) throws IOException, InterruptedException {
        final URI uri = URI.create(skinUrl);
        final String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("Unsupported skin URI scheme: " + scheme);
        }

        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "squaremap/1.3.2 player-head-cache")
            .GET()
            .build();
        final HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                throw new IOException("Skin request returned HTTP " + response.statusCode());
            }
        }

        final BufferedImage skin;
        try (InputStream body = response.body()) {
            skin = ImageIO.read(body);
        }
        if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16) {
            throw new IOException("Invalid Minecraft skin image");
        }

        final BufferedImage head = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = head.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.drawImage(skin, 0, 0, 16, 16, 8, 8, 16, 16, null);
            // Hat/helmet overlay.
            graphics.drawImage(skin, 0, 0, 16, 16, 40, 8, 48, 16, null);
        } finally {
            graphics.dispose();
        }

        Files.createDirectories(target.getParent());
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (!ImageIO.write(head, "png", temporary.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicMoveFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static @Nullable String skinUrl(final ServerPlayer player) {
        final Collection<?> textures = player.getGameProfile().getProperties().get("textures");
        if (textures == null || textures.isEmpty()) {
            return null;
        }

        for (final Object property : textures) {
            final @Nullable String value = propertyValue(property);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                final String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                final Map<?, ?> root = Util.gson().fromJson(json, Map.class);
                final Object texturesObject = root.get("textures");
                if (!(texturesObject instanceof Map<?, ?> textureMap)) {
                    continue;
                }
                final Object skinObject = textureMap.get("SKIN");
                if (!(skinObject instanceof Map<?, ?> skinMap)) {
                    continue;
                }
                final Object url = skinMap.get("url");
                if (url instanceof String string && !string.isBlank()) {
                    return string;
                }
            } catch (final IllegalArgumentException ignored) {
                // Malformed Base64 or URI data. Try another textures property.
            }
        }
        return null;
    }

    private static @Nullable String propertyValue(final Object property) {
        for (final String methodName : new String[] {"value", "getValue"}) {
            try {
                final Method method = property.getClass().getMethod(methodName);
                final Object value = method.invoke(property);
                if (value instanceof String string) {
                    return string;
                }
            } catch (final NoSuchMethodException ignored) {
                // Try the authlib API used by another Minecraft version.
            } catch (final IllegalAccessException | InvocationTargetException ignored) {
                return null;
            }
        }
        return null;
    }
}
