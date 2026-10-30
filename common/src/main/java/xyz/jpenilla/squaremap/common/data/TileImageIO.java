package xyz.jpenilla.squaremap.common.data;

import com.luciad.imageio.webp.WebPImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

/** Tile decoding isolated from other mods' global ImageIO provider ordering. */
@DefaultQualifier(NonNull.class)
final class TileImageIO {
    private TileImageIO() {
    }

    static BufferedImage read(final Path file) throws IOException {
        final String format = file.getFileName().toString().endsWith(".webp") ? "webp" : "png";
        final IOException failure = new IOException("No image reader could decode map tile " + file);
        final List<ImageReader> readers = new ArrayList<>();
        // Prefer the WebP codec bundled with squaremap over unrelated mods' codecs.
        if (format.equals("webp")) {
            try {
                readers.add(new WebPImageReaderSpi().createReaderInstance());
            } catch (final IOException | RuntimeException | LinkageError ex) {
                failure.addSuppressed(ex);
            }
        }
        ImageIO.getImageReadersByFormatName(format).forEachRemaining(reader -> {
            if (format.equals("webp") && reader.getClass().getName().startsWith("com.luciad.imageio.webp.")) {
                reader.dispose();
            } else {
                readers.add(reader);
            }
        });
        try {
            for (final ImageReader reader : readers) {
                // Each decoder gets a fresh stream, including after a failed attempt.
                try (FileImageInputStream input = new FileImageInputStream(file.toFile())) {
                    reader.setInput(input);
                    if (reader.getWidth(0) != Image.SIZE || reader.getHeight(0) != Image.SIZE) {
                        throw new IOException("Map tiles must be " + Image.SIZE + "x" + Image.SIZE);
                    }
                    final BufferedImage image = reader.read(0);
                    if (image == null || image.getWidth() != Image.SIZE || image.getHeight() != Image.SIZE) {
                        throw new IOException("Decoder returned an invalid map tile");
                    }
                    return image;
                } catch (final IOException | RuntimeException | LinkageError ex) {
                    failure.addSuppressed(ex);
                }
            }
        } finally {
            readers.forEach(ImageReader::dispose);
        }
        throw failure;
    }
}
