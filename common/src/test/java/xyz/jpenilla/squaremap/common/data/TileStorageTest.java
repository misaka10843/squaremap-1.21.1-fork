package xyz.jpenilla.squaremap.common.data;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.jpenilla.squaremap.common.config.Config;

import static org.junit.jupiter.api.Assertions.*;

class TileStorageTest {
    @TempDir Path directory;

    @BeforeEach
    void configure() {
        Config.TILE_FORMAT = "png";
        Config.TILE_KEEP_PNG = false;
    }

    @Test
    void corruptPrimaryRecoversAlternateAndPreservesOriginal() throws Exception {
        Config.TILE_FORMAT = "webp";
        Config.TILE_KEEP_PNG = true;
        final Path zoom = Files.createDirectories(this.directory.resolve("0"));
        Files.writeString(zoom.resolve("-11_7.webp"), "broken webp");
        final BufferedImage old = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        old.setRGB(20, 30, 0xFF123456);
        ImageIO.write(old, "png", zoom.resolve("-11_7.png").toFile());
        final Image changes = new Image(new RegionCoordinate(-11, 7), this.directory, 0);
        changes.setPixel(1, 2, 0xFFABCDEF);
        final TileStorage storage = new TileStorage(this.directory, 0);
        storage.applyNative(changes);
        final BufferedImage saved = TileImageIO.read(zoom.resolve("-11_7.webp"));
        assertEquals(0xFF123456, saved.getRGB(20, 30));
        assertEquals(0xFFABCDEF, saved.getRGB(1, 2));
        storage.compact(false);
        try (var files = Files.list(zoom)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("-11_7.webp.unreadable-")));
        }
    }

    @Test
    void wrongSizeTileDoesNotBreakFutureSaves() throws Exception {
        final Path zoom = Files.createDirectories(this.directory.resolve("0"));
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", zoom.resolve("0_0.png").toFile());
        final Image changes = new Image(new RegionCoordinate(0, 0), this.directory, 0);
        changes.setPixel(511, 511, 0xFF123456);
        new TileStorage(this.directory, 0).applyNative(changes);
        assertEquals(0xFF123456, TileImageIO.read(zoom.resolve("0_0.png")).getRGB(511, 511));
    }

    @Test
    void downsampleUsesLinearLightAndAlphaCoverage() {
        final BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFFFFFFFF);
        source.setRGB(1, 0, 0xFF000000);
        source.setRGB(0, 1, 0xFFFFFFFF);
        source.setRGB(1, 1, 0xFF000000);
        source.setRGB(2, 0, 0xFFFF0000);
        final BufferedImage target = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ZoomDownsampler.downsample(source, target, 0, 0);
        assertEquals(0xFFBCBCBC, target.getRGB(0, 0));
        assertEquals(0x40FF0000, target.getRGB(1, 0));
        assertEquals(0, target.getRGB(1, 1));
    }

    @Test
    void parentKeepsPreviouslyRenderedChildrenAtNegativeCoordinates() throws Exception {
        final TileStorage storage = new TileStorage(this.directory, 1);
        final Image left = new Image(new RegionCoordinate(-2, -2), this.directory, 1);
        final Image right = new Image(new RegionCoordinate(-1, -2), this.directory, 1);
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                left.setPixel(x, z, 0xFFFF0000);
                right.setPixel(x, z, 0xFF00FF00);
            }
        }
        storage.applyNative(left);
        storage.rebuildParent(0, -1, -1);
        storage.applyNative(right);
        storage.rebuildParent(0, -1, -1);
        final BufferedImage parent = TileImageIO.read(this.directory.resolve("0/-1_-1.png"));
        assertEquals(0xFFFF0000, parent.getRGB(0, 0));
        assertEquals(0xFF00FF00, parent.getRGB(256, 0));
    }
}
