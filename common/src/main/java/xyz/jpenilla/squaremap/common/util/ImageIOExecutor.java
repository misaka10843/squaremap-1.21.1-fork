package xyz.jpenilla.squaremap.common.util;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.Semaphore;
import net.minecraft.server.level.ServerLevel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.data.Image;
import xyz.jpenilla.squaremap.common.data.RegionCoordinate;
import xyz.jpenilla.squaremap.common.data.TileStorage;
import xyz.jpenilla.squaremap.common.httpd.EventStream;
import xyz.jpenilla.squaremap.common.util.Util;

/** Coalescing tile writer. At most one pending image is kept for each native region. */
@DefaultQualifier(NonNull.class)
public final class ImageIOExecutor {
    private static final int MAX_PENDING_REGIONS = 100;
    private final ExecutorService executor;
    private final TileStorage storage;
    private final int maxZoom;
    private final EventStream eventStream;
    private final String worldName;
    private final Map<RegionCoordinate, Image> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();
    private final Semaphore pendingSlots = new Semaphore(MAX_PENDING_REGIONS);
    private volatile boolean closed;

    private ImageIOExecutor(final ServerLevel level, final TileStorage storage, final int maxZoom, final EventStream eventStream) {
        this.storage = storage;
        this.maxZoom = maxZoom;
        this.eventStream = eventStream;
        this.worldName = Util.levelWebName(level);
        this.executor = Executors.newSingleThreadExecutor(Util.squaremapThreadFactory("imageio", level));
    }

    public void saveImage(final Image image) {
        if (this.closed) {
            return;
        }
        while (true) {
            synchronized (this.pending) {
                if (this.closed) {
                    return;
                }
                final Image existing = this.pending.get(image.region());
                if (existing != null) {
                    existing.merge(image);
                    this.scheduleWorker();
                    break;
                }
            }
            try {
                if (!this.pendingSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    continue;
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (this.pending) {
                if (this.closed) {
                    this.pendingSlots.release();
                    return;
                }
                final Image existing = this.pending.putIfAbsent(image.region(), image);
                if (existing == null) {
                    this.scheduleWorker();
                    break;
                }
                existing.merge(image);
                this.pendingSlots.release();
                break;
            }
        }
    }

    private void scheduleWorker() {
        if (!this.workerScheduled.compareAndSet(false, true)) {
            return;
        }
        this.executor.execute(this::drain);
    }

    private void drain() {
        try {
            // Brief debounce merges bursts from the same region before any encode occurs.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            while (!this.pending.isEmpty()) {
                final Map<RegionCoordinate, Image> batch = new java.util.HashMap<>();
                synchronized (this.pending) {
                    this.pending.forEach((region, image) -> {
                        if (this.pending.remove(region, image)) {
                            batch.put(region, image);
                            this.pendingSlots.release();
                        }
                    });
                }
                if (batch.isEmpty()) {
                    continue;
                }
                final Set<TileKey> dirtyParents = new HashSet<>();
                final Set<TileKey> changedTiles = new HashSet<>();
                batch.forEach((region, image) -> {
                    try {
                        this.storage.applyNative(image);
                        changedTiles.add(new TileKey(this.maxZoom, region.x(), region.z()));
                        if (this.maxZoom > 0) {
                            final int x = Math.floorDiv(region.x(), 2);
                            final int z = Math.floorDiv(region.z(), 2);
                            dirtyParents.add(new TileKey(this.maxZoom - 1, x, z));
                        }
                    } catch (final IOException ex) {
                        Logging.logger().error("Could not save map region [{}, {}]", region.x(), region.z(), ex);
                    }
                });
                this.rebuildParents(dirtyParents, changedTiles);
                this.publishInvalidations(changedTiles);
            }
        } finally {
            synchronized (this.pending) {
                this.workerScheduled.set(false);
                if (!this.closed && !this.pending.isEmpty()) {
                    this.scheduleWorker();
                }
            }
        }
    }

    private void rebuildParents(Set<TileKey> current, final Set<TileKey> changed) {
        while (!current.isEmpty()) {
            final Set<TileKey> next = new HashSet<>();
            for (final TileKey tile : current) {
                try {
                    this.storage.rebuildParent(tile.zoom, tile.x, tile.z);
                    changed.add(tile);
                    if (tile.zoom > 0) {
                        next.add(new TileKey(tile.zoom - 1, Math.floorDiv(tile.x, 2), Math.floorDiv(tile.z, 2)));
                    }
                } catch (final IOException ex) {
                    Logging.logger().error("Could not rebuild parent map tile {}/{}_{}", tile.zoom, tile.x, tile.z, ex);
                }
            }
            current = next;
        }
    }

    private void publishInvalidations(final Set<TileKey> changed) {
        if (changed.isEmpty()) {
            return;
        }
        final var tiles = changed.stream().map(tile -> Map.of(
            "z", tile.zoom,
            "x", tile.x,
            "y", tile.z
        )).toList();
        this.eventStream.publish("tiles-invalidated", Util.gson().toJson(Map.of(
            "world", this.worldName,
            "tiles", tiles
        )), "base");
    }

    public void shutdown() {
        synchronized (this.pending) {
            this.closed = true;
        }
        Util.shutdownExecutor(this.executor, TimeUnit.SECONDS, 30L);
    }

    public CompletableFuture<TileStorage.Stats> compact(final boolean migrate) {
        final CompletableFuture<TileStorage.Stats> result = new CompletableFuture<>();
        synchronized (this.pending) {
            if (this.closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Tile writer is closed"));
            }
            this.scheduleWorker();
            this.executor.execute(() -> {
                try {
                    result.complete(this.storage.compact(migrate));
                } catch (final Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        }
        return result;
    }

    public static ImageIOExecutor create(final ServerLevel level, final TileStorage storage, final int maxZoom, final EventStream eventStream) {
        return new ImageIOExecutor(level, storage, maxZoom, eventStream);
    }

    private record TileKey(int zoom, int x, int z) {
    }
}
