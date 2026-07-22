package xyz.jpenilla.squaremap.common.data;

import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

/** A sparse set of pixel changes for one 512 by 512 native map tile. */
@DefaultQualifier(NonNull.class)
public final class Image {
    public static final int SIZE = 512;
    private final RegionCoordinate region;
    private int @Nullable [] pixels;

    public Image(final RegionCoordinate region, final java.nio.file.Path ignoredDirectory, final int ignoredMaxZoom) {
        this.region = region;
    }

    public RegionCoordinate region() {
        return this.region;
    }

    public synchronized void setPixel(final int x, final int z, final int color) {
        if (this.pixels == null) {
            this.pixels = new int[SIZE * SIZE];
            Arrays.fill(this.pixels, Integer.MIN_VALUE);
        }
        this.pixels[(z & (SIZE - 1)) * SIZE + (x & (SIZE - 1))] = color;
    }

    /** Merge newer changes into this pending tile. */
    public void merge(final Image newer) {
        final int @Nullable [] update = newer.snapshotPixels();
        if (update == null) {
            return;
        }
        synchronized (this) {
            if (this.pixels == null) {
                this.pixels = update;
                return;
            }
            for (int i = 0; i < update.length; i++) {
                if (update[i] != Integer.MIN_VALUE) {
                    this.pixels[i] = update[i];
                }
            }
        }
    }

    int @Nullable [] snapshotPixels() {
        synchronized (this) {
            return this.pixels == null ? null : this.pixels.clone();
        }
    }
}
