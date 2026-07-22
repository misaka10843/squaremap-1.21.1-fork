package xyz.jpenilla.squaremap.common.data;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.util.FileUtil;

/** Reads, writes, compacts and migrates the tile pyramid for one world. */
@DefaultQualifier(NonNull.class)
public final class TileStorage {
    private static final int TRANSPARENT = new Color(0, 0, 0, 0).getRGB();
    private final Path directory;
    private final int maxZoom;

    public TileStorage(final Path directory, final int maxZoom) {
        this.directory = directory;
        this.maxZoom = maxZoom;
    }

    public void applyNative(final Image changes) throws IOException {
        final int @Nullable [] pixels = changes.snapshotPixels();
        if (pixels == null) {
            return;
        }
        final RegionCoordinate region = changes.region();
        final BufferedImage image = this.readOrEmpty(this.maxZoom, region.x(), region.z());
        for (int i = 0; i < pixels.length; i++) {
            final int pixel = pixels[i];
            if (pixel != Integer.MIN_VALUE) {
                image.setRGB(i % Image.SIZE, i / Image.SIZE, pixel == 0 ? TRANSPARENT : pixel);
            }
        }
        this.writeOrDelete(this.maxZoom, region.x(), region.z(), image);
    }

    /** Rebuild one parent from its four children using the original nearest-pixel sampling. */
    public void rebuildParent(final int zoom, final int x, final int z) throws IOException {
        if (zoom < 0 || zoom >= this.maxZoom) {
            return;
        }
        final BufferedImage parent = emptyImage();
        final int childZoom = zoom + 1;
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final @Nullable BufferedImage child = this.read(childZoom, x * 2 + childX, z * 2 + childZ);
                if (child == null) {
                    continue;
                }
                final int baseX = childX * 256;
                final int baseZ = childZ * 256;
                for (int dz = 0; dz < 256; dz++) {
                    for (int dx = 0; dx < 256; dx++) {
                        parent.setRGB(baseX + dx, baseZ + dz, child.getRGB(dx * 2, dz * 2));
                    }
                }
            }
        }
        this.writeOrDelete(zoom, x, z, parent);
    }

    public Stats compact(final boolean migrate) throws IOException {
        long deleted = 0L;
        long migrated = 0L;
        long bytesFreed = 0L;
        if (!Files.isDirectory(this.directory)) {
            return new Stats(0L, 0L, 0L);
        }
        try (var files = Files.walk(this.directory)) {
            for (final Iterator<Path> it = files.filter(Files::isRegularFile).iterator(); it.hasNext();) {
                final Path file = it.next();
                final String name = file.getFileName().toString();
                final Path parent = file.getParent();
                try {
                    if (parent != null && parent.getParent() != null
                        && parent.getParent().equals(this.directory)
                        && Integer.parseInt(parent.getFileName().toString()) > this.maxZoom) {
                        bytesFreed += Files.size(file);
                        Files.deleteIfExists(file);
                        deleted++;
                        continue;
                    }
                } catch (final NumberFormatException ignored) {
                    // Non-zoom directory; normal extension filtering below applies.
                }
                if (name.endsWith(".tmp")) {
                    bytesFreed += Files.size(file);
                    Files.deleteIfExists(file);
                    deleted++;
                    continue;
                }
                if (!name.endsWith(".png") && !name.endsWith(".webp")) {
                    continue;
                }
                final String extension = name.substring(name.lastIndexOf('.') + 1);
                final String stem = name.substring(0, name.lastIndexOf('.'));
                if (!extension.equals(Config.TILE_FORMAT)
                    && !(Config.TILE_KEEP_PNG && extension.equals("png"))
                    && Files.isRegularFile(file.resolveSibling(stem + '.' + Config.TILE_FORMAT))) {
                    bytesFreed += Files.size(file);
                    Files.deleteIfExists(file);
                    deleted++;
                    continue;
                }
                final @Nullable BufferedImage image;
                try {
                    image = ImageIO.read(file.toFile());
                } catch (final IOException decodeFailure) {
                    Logging.logger().warn("Skipping unreadable tile during maintenance: {}", file, decodeFailure);
                    continue;
                }
                if (image != null && isTransparent(image)) {
                    bytesFreed += Files.size(file);
                    Files.deleteIfExists(file);
                    deleted++;
                    continue;
                }
                if (migrate && image != null && !name.endsWith('.' + Config.TILE_FORMAT)) {
                    final Path target = file.resolveSibling(stem + '.' + Config.TILE_FORMAT);
                    writeImage(target, image, Config.TILE_FORMAT);
                    migrated++;
                    if (!(Config.TILE_KEEP_PNG && name.endsWith(".png"))) {
                        bytesFreed += Files.size(file);
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
        return new Stats(deleted, migrated, bytesFreed);
    }

    private BufferedImage readOrEmpty(final int zoom, final int x, final int z) throws IOException {
        final @Nullable BufferedImage read = this.read(zoom, x, z);
        return read == null ? emptyImage() : read;
    }

    private @Nullable BufferedImage read(final int zoom, final int x, final int z) throws IOException {
        final Path primary = this.path(zoom, x, z, Config.TILE_FORMAT);
        Path source = primary;
        if (!Files.isRegularFile(source)) {
            source = this.path(zoom, x, z, Config.TILE_FORMAT.equals("png") ? "webp" : "png");
        }
        if (!Files.isRegularFile(source)) {
            return null;
        }
        final @Nullable BufferedImage result = ImageIO.read(source.toFile());
        if (result == null) {
            Logging.logger().warn("Could not decode map tile '{}'; replacing it", source);
            Files.deleteIfExists(source);
        }
        return result;
    }

    private void writeOrDelete(final int zoom, final int x, final int z, final BufferedImage image) throws IOException {
        if (isTransparent(image)) {
            Files.deleteIfExists(this.path(zoom, x, z, "png"));
            Files.deleteIfExists(this.path(zoom, x, z, "webp"));
            return;
        }
        final Path primary = this.path(zoom, x, z, Config.TILE_FORMAT);
        writeImage(primary, image, Config.TILE_FORMAT);
        if (Config.TILE_FORMAT.equals("webp") && Config.TILE_KEEP_PNG) {
            writeImage(this.path(zoom, x, z, "png"), image, "png");
        } else if (!Config.TILE_KEEP_PNG) {
            final String obsolete = Config.TILE_FORMAT.equals("png") ? "webp" : "png";
            Files.deleteIfExists(this.path(zoom, x, z, obsolete));
        }
    }

    private Path path(final int zoom, final int x, final int z, final String format) throws IOException {
        final Path dir = this.directory.resolve(Integer.toString(zoom));
        Files.createDirectories(dir);
        return dir.resolve(x + "_" + z + '.' + format);
    }

    private static void writeImage(final Path out, final BufferedImage image, final String format) throws IOException {
        FileUtil.atomicWrite(out, tmp -> {
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
                if (!writers.hasNext()) {
                    throw new IOException("No ImageIO writer is installed for tile format '" + format + "'");
                }
                final ImageWriter writer = writers.next();
                try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(stream)) {
                    writer.setOutput(imageOut);
                    final ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        if (format.equals("webp")) {
                            final String[] types = param.getCompressionTypes();
                            if (types != null) {
                                for (final String type : types) {
                                    if (type.equalsIgnoreCase("lossless")) {
                                        param.setCompressionType(type);
                                        break;
                                    }
                                }
                            }
                            param.setCompressionQuality(1.0F);
                        } else if (Config.COMPRESS_IMAGES) {
                            final String[] types = param.getCompressionTypes();
                            if (param.getCompressionType() == null && types != null && types.length > 0) {
                                param.setCompressionType(types[0]);
                            }
                            param.setCompressionQuality(Config.COMPRESSION_RATIO);
                        }
                    }
                    writer.write(null, new IIOImage(image, null, null), param);
                } finally {
                    writer.dispose();
                }
            }
        });
    }

    private static boolean isTransparent(final BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage emptyImage() {
        return new BufferedImage(Image.SIZE, Image.SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    public record Stats(long deletedFiles, long migratedFiles, long bytesFreed) {
    }
}
