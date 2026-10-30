package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.nbt.CompoundTag;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Optional NoCopyNBTData support without requiring newer Moonrise on 1.21.1. */
final class MoonriseSnapshotIO {
    private static final @Nullable Class<?> NO_COPY = findNoCopy();

    private MoonriseSnapshotIO() {
    }

    private static @Nullable Class<?> findNoCopy() {
        try {
            return Class.forName(MoonriseRegionFileIO.class.getName() + "$NoCopyNBTData", false, MoonriseRegionFileIO.class.getClassLoader());
        } catch (final ClassNotFoundException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static BiConsumer<CompoundTag, Throwable> callback(final CompletableFuture<@Nullable CompoundTag> future) {
        final BiConsumer<CompoundTag, Throwable> callback = (tag, error) -> {
            if (error != null) future.completeExceptionally(error);
            else future.complete(tag);
        };
        if (NO_COPY == null) return callback;
        return (BiConsumer<CompoundTag, Throwable>) Proxy.newProxyInstance(NO_COPY.getClassLoader(), new Class<?>[]{BiConsumer.class, NO_COPY},
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "accept" -> {
                        callback.accept((CompoundTag) args[0], (Throwable) args[1]);
                        yield null;
                    }
                    case "toString" -> "squaremap NoCopyNBTData callback";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            });
    }
}
