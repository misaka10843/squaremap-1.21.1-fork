package xyz.jpenilla.squaremap.common.render.output;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.coordinate.TileCoordinate;
import xyz.jpenilla.squaremap.common.data.TileStorage;

/** Keeps the fork's WebP, atomic writes, transparent-tile deletion and recovery behavior. */
final class TileImages {
    static final int SIZE = 512;
    private final Path directory;
    private final TileStorage storage;

    TileImages(final Path directory, final int maxZoom) {
        this.directory = directory;
        this.storage = new TileStorage(directory, maxZoom);
    }

    Path path(final TileCoordinate tile) {
        return this.directory.resolve(Integer.toString(tile.zoom())).resolve(tile.x() + "_" + tile.z() + '.' + Config.TILE_FORMAT);
    }

    BufferedImage read(final TileCoordinate tile) throws IOException {
        return this.storage.readTile(tile.zoom(), tile.x(), tile.z());
    }

    void write(final TileCoordinate tile, final BufferedImage image) throws IOException {
        this.storage.writeTile(tile.zoom(), tile.x(), tile.z(), image);
    }

    TileStorage.Stats compact(final boolean migrate) throws IOException {
        return this.storage.compact(migrate);
    }
}
