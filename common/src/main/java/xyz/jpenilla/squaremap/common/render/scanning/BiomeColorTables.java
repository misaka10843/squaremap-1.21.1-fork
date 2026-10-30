package xyz.jpenilla.squaremap.common.render.scanning;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.config.WorldAdvanced;
import xyz.jpenilla.squaremap.common.util.Colors;
import xyz.jpenilla.squaremap.common.util.Util;
import xyz.jpenilla.squaremap.common.util.BiomeClimateAccess;

public record BiomeColorTables(
    Reference2IntMap<Biome> grassColors,
    Reference2IntMap<Biome> foliageColors,
    Reference2IntMap<Biome> dryFoliageColors,
    Reference2IntMap<Biome> waterColors
) {
    private static int[] MAP_GRASS;
    private static int[] MAP_FOLIAGE;
    private static int[] MAP_DRY_FOLIAGE;

    public static void loadImages(final DirectoryProvider directories) {
        final Path imagesDir = directories.webDirectory().resolve("images");
        final BufferedImage imgGrass;
        final BufferedImage imgFoliage;
        final BufferedImage imgDryFoliage;

        try {
            imgGrass = readColorMap(imagesDir, "grass.png");
            imgFoliage = readColorMap(imagesDir, "foliage.png");
            imgDryFoliage = readColorMap(imagesDir, "dry_foliage.png");
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read biome images", e);
        }

        MAP_GRASS = toArray(imgGrass);
        MAP_FOLIAGE = toArray(imgFoliage);
        MAP_DRY_FOLIAGE = toArray(imgDryFoliage);
    }

    static BufferedImage readColorMap(final Path directory, final String name) throws IOException {
        final BufferedImage image;
        final Path file = directory.resolve(name);
        if (Files.isRegularFile(file)) {
            image = ImageIO.read(file.toFile());
        } else {
            try (InputStream resource = BiomeColorTables.class.getResourceAsStream("/web/images/" + name)) {
                if (resource == null) throw new IOException("Missing biome colormap: " + name);
                image = ImageIO.read(resource);
            }
        }
        if (image == null || image.getWidth() != 256 || image.getHeight() != 256) {
            throw new IOException("Biome colormap must be a readable 256x256 image: " + name);
        }
        return image;
    }

    public static BiomeColorTables create(final ServerLevel level, final WorldAdvanced advanced) {
        final Reference2IntMap<Biome> grassColors = new Reference2IntOpenHashMap<>();
        final Reference2IntMap<Biome> foliageColors = new Reference2IntOpenHashMap<>();
        final Reference2IntMap<Biome> dryFoliageColors = new Reference2IntOpenHashMap<>();
        final Reference2IntMap<Biome> waterColors = new Reference2IntOpenHashMap<>();

        for (final Biome biome : Util.biomeRegistry(level)) {
            float temperature = Mth.clamp(biome.getBaseTemperature(), 0.0F, 1.0F);
            float humidity = Mth.clamp(downfall(biome), 0.0F, 1.0F);
            grassColors.put(
                biome,
                biome.getSpecialEffects().getGrassColorOverride()
                    .orElse(defaultGrassColor(temperature, humidity))
                    .intValue()
            );
            foliageColors.put(
                biome,
                biome.getSpecialEffects().getFoliageColorOverride()
                    .orElse(Colors.mix(Colors.plantMapColor(), defaultFoliageColor(temperature, humidity), 0.85f))
                    .intValue()
            );
            dryFoliageColors.put(
                biome,
                defaultDryFoliageColor(temperature, humidity)
            );
            waterColors.put(
                biome,
                biome.getSpecialEffects().getWaterColor()
            );
        }

        grassColors.putAll(advanced.COLOR_OVERRIDES_BIOME_GRASS);
        foliageColors.putAll(advanced.COLOR_OVERRIDES_BIOME_FOLIAGE);
        dryFoliageColors.putAll(advanced.COLOR_OVERRIDES_BIOME_DRY_FOLIAGE);
        waterColors.putAll(advanced.COLOR_OVERRIDES_BIOME_WATER);

        return new BiomeColorTables(
            Reference2IntMaps.unmodifiable(grassColors),
            Reference2IntMaps.unmodifiable(foliageColors),
            Reference2IntMaps.unmodifiable(dryFoliageColors),
            Reference2IntMaps.unmodifiable(waterColors)
        );
    }

    private static float downfall(final Biome biome) {
        return ((BiomeClimateAccess) (Object) biome).squaremap$downfall();
    }

    private static int[] toArray(final BufferedImage image) {
        final int[] array = new int[256 * 256];
        for (int x = 0; x < 256; ++x) {
            for (int y = 0; y < 256; ++y) {
                final int color = image.getRGB(x, y);
                final int r = color >> 16 & 0xFF;
                final int g = color >> 8 & 0xFF;
                final int b = color & 0xFF;
                array[x + y * 256] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return array;
    }

    private static int defaultGrassColor(final double temperature, final double humidity) {
        int j = (int) ((1.0 - (humidity * temperature)) * 255.0);
        int i = (int) ((1.0 - temperature) * 255.0);
        int k = j << 8 | i;
        if (k > MAP_GRASS.length) {
            return 0;
        }
        return MAP_GRASS[k];
    }

    private static int defaultFoliageColor(final double temperature, final double humidity) {
        int i = (int) ((1.0 - temperature) * 255.0);
        int j = (int) ((1.0 - (humidity * temperature)) * 255.0);
        return MAP_FOLIAGE[(j << 8 | i)];
    }

    private static int defaultDryFoliageColor(final double temperature, final double humidity) {
        int i = (int) ((1.0 - temperature) * 255.0);
        int j = (int) ((1.0 - (humidity * temperature)) * 255.0);
        return MAP_DRY_FOLIAGE[(j << 8 | i)];
    }
}
