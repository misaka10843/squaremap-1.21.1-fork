package xyz.jpenilla.squaremap.common.render.scanning;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class BiomeColorTablesTest {
    @TempDir Path directory;

    @Test
    void oldWebDirectoriesCanUseBundledDryFoliageMap() throws Exception {
        final BufferedImage image = BiomeColorTables.readColorMap(this.directory, "dry_foliage.png");
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
    }

    @Test
    void invalidCustomMapReportsAnError() throws Exception {
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", this.directory.resolve("grass.png").toFile());
        assertThrows(IOException.class, () -> BiomeColorTables.readColorMap(this.directory, "grass.png"));
    }
}
