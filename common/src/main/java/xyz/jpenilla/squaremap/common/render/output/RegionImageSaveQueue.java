package xyz.jpenilla.squaremap.common.render.output;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.ImageSavingConfig;
import xyz.jpenilla.squaremap.common.config.Messages;
import xyz.jpenilla.squaremap.common.coordinate.TileCoordinate;
import xyz.jpenilla.squaremap.common.util.concurrent.KeyedSerialExecutor;
import xyz.jpenilla.squaremap.common.util.concurrent.Threads;
import xyz.jpenilla.squaremap.common.util.Util;
import xyz.jpenilla.squaremap.common.data.TileStorage;

/**
 * Saves region images to their max zoom tile, then downsamples each written tile into its parent up to zoom 0.
 * Updates to the same tile run one at a time in the order they became ready to run.
 */
@DefaultQualifier(NonNull.class)
public final class RegionImageSaveQueue {
    private final TileImages tiles;
    private final ExecutorService executor;
    private final KeyedSerialExecutor<TileCoordinate> tileExecutor;
    private final int maxPendingImages;
    private final Consumer<TileCoordinate> written;
    private final ReentrantLock lock = new ReentrantLock();
    // Signaled when a save finishes or the queue closes
    private final Condition changed = this.lock.newCondition();
    private int inFlight;
    private boolean closed;
    private boolean maintenance;

    RegionImageSaveQueue(
        final Path tilesDirectory,
        final int workers,
        final int maxPendingImages,
        final ThreadFactory factory,
        final Consumer<TileCoordinate> written
    ) {
        this(tilesDirectory, workers, maxPendingImages, factory, written, Integer.MAX_VALUE);
    }

    private RegionImageSaveQueue(final Path tilesDirectory, final int workers, final int maxPendingImages,
                                 final ThreadFactory factory, final Consumer<TileCoordinate> written, final int maxZoom) {
        this.tiles = new TileImages(tilesDirectory, maxZoom);
        this.executor = Executors.newFixedThreadPool(workers, factory);
        this.tileExecutor = new KeyedSerialExecutor<>(this.executor);
        this.maxPendingImages = maxPendingImages;
        this.written = written;
    }

    /**
     * Waits for queue capacity and schedules the image's writes without waiting for completion.
     * Its pixels must not be modified after this call because the writes use them asynchronously.
     *
     * @param image the image to save
     * @throws InterruptedException if interrupted while waiting for queue capacity
     * @throws IllegalStateException if the queue has been shut down
     */
    public void saveImage(final RegionImage image) throws InterruptedException {
        if (image.isEmpty()) {
            return;
        }
        this.lock.lockInterruptibly();
        try {
            while (!this.closed && (this.inFlight == this.maxPendingImages || this.maintenance)) {
                this.changed.await();
            }
            if (this.closed) {
                throw new IllegalStateException("Image save queue is shut down");
            }
            this.inFlight++;
        } finally {
            this.lock.unlock();
        }
        try {
            this.tileExecutor.execute(image.tile(), () -> this.save(image.tile(), image::drawInto));
        } catch (final RuntimeException ex) {
            this.finished();
            throw ex;
        }
    }

    private void finished() {
        this.lock.lock();
        try {
            this.inFlight--;
            this.changed.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    // Runs as the tile's task, and must not throw so the save is always finished
    private void save(final TileCoordinate tile, final java.util.function.Predicate<BufferedImage> update) {
        final BufferedImage image;
        final boolean changed;
        try {
            image = this.tiles.read(tile);
            changed = update.test(image) || this.tiles.needsWrite(tile, image);
            if (changed) this.tiles.write(tile, image);
        } catch (final Throwable ex) {
            try {
                Logging.logger().error("Could not save map tile {}", this.tiles.path(tile), ex);
            } finally {
                this.finished();
            }
            return;
        }
        try {
            if (changed) this.written.accept(tile);
        } catch (final Throwable ex) {
            Logging.logger().warn("Failed to report written tile {}", tile, ex);
        }
        if (tile.zoom() == 0) {
            this.finished();
            return;
        }
        // Even an unchanged child must rebuild missing/outdated parents (e.g. after a downsampling upgrade).
        // Queued before this tile's next update can run, so parent updates run in the order their sources were written
        this.tileExecutor.execute(tile.parent(), () -> this.save(tile.parent(), parent -> downsampleInto(tile, image, parent)));
    }

    private static boolean downsampleInto(final TileCoordinate child, final BufferedImage childImage, final BufferedImage parent) {
        // The child covers one quadrant of its parent
        final int half = TileImages.SIZE / 2;
        return ZoomDownsampler.downsample(childImage, parent, (child.x() & 1) * half, (child.z() & 1) * half);
    }

    /**
     * Rejects further saves, waits for queued saves to finish, then stops the workers.
     * Interruptions do not stop the wait; the caller's interrupt status is restored when the wait finishes.
     */
    public void shutdown() {
        this.lock.lock();
        try {
            this.closed = true;
            this.changed.signalAll();
            while (this.inFlight > 0 || this.maintenance) {
                this.changed.awaitUninterruptibly();
            }
        } finally {
            this.lock.unlock();
        }
        this.executor.close();
    }

    public static RegionImageSaveQueue create(
        final ServerLevel level,
        final ImageSavingConfig config,
        final Path tilesDirectory,
        final int maxZoom,
        final Consumer<TileCoordinate> written
    ) {
        return new RegionImageSaveQueue(
            tilesDirectory,
            config.workers,
            config.maxPendingImages,
            Threads.platformThreads(Threads.name("imageio", Util.worldIdentifier(level))),
            written,
            maxZoom
        );
    }

    public CompletableFuture<TileStorage.Stats> compact(final boolean migrate) {
        final CompletableFuture<TileStorage.Stats> result = new CompletableFuture<>();
        Thread.ofVirtual().name("squaremap-tile-maintenance").start(() -> {
            boolean claimed = false;
            TileStorage.Stats stats = null;
            Throwable failure = null;
            try {
                this.lock.lockInterruptibly();
                try {
                    while (!this.closed && this.maintenance) this.changed.await();
                    if (this.closed) throw new IllegalStateException("Image save queue is shut down");
                    this.maintenance = true;
                    claimed = true;
                    while (this.inFlight > 0) this.changed.await();
                } finally {
                    this.lock.unlock();
                }
                stats = this.tiles.compact(migrate);
            } catch (final Throwable error) {
                failure = error;
            } finally {
                if (claimed) {
                    this.lock.lock();
                    try {
                        this.maintenance = false;
                        this.changed.signalAll();
                    } finally {
                        this.lock.unlock();
                    }
                }
            }
            if (failure != null) result.completeExceptionally(failure);
            else result.complete(stats);
        });
        return result;
    }
}
