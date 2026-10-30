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

    boolean needsWrite(final TileCoordinate tile, final BufferedImage image) {
        boolean transparent = true;
        final int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight() && transparent; y++) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (final int pixel : row) if ((pixel >>> 24) != 0) { transparent = false; break; }
        }
        final Path png = this.directory.resolve(Integer.toString(tile.zoom())).resolve(tile.x() + "_" + tile.z() + ".png");
        final Path webp = this.directory.resolve(Integer.toString(tile.zoom())).resolve(tile.x() + "_" + tile.z() + ".webp");
        if (transparent) return java.nio.file.Files.exists(png) || java.nio.file.Files.exists(webp);
        if (!java.nio.file.Files.isRegularFile(this.path(tile))) return true;
        if (Config.TILE_FORMAT.equals("webp") && Config.TILE_KEEP_PNG) return !java.nio.file.Files.isRegularFile(png);
        return !Config.TILE_KEEP_PNG && java.nio.file.Files.exists(Config.TILE_FORMAT.equals("png") ? webp : png);
    }

    TileStorage.Stats compact(final boolean migrate) throws IOException {
        return this.storage.compact(migrate);
    }
}
