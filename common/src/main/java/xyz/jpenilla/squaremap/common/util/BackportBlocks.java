package xyz.jpenilla.squaremap.common.util;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/** Optional registry-based support; never links newer Blocks constants or client-only classes. */
public final class BackportBlocks {
    public static final Map<String, String> COLORS = Map.ofEntries(
        Map.entry("minecraft:pale_oak_leaves", "#626760"),
        Map.entry("minecraft:wildflowers", "#EDD575"),
        Map.entry("minecraft:golden_dandelion", "#DBA213"),
        Map.entry("minecraft:torchflower", "#F6B927"),
        Map.entry("minecraft:pitcher_plant", "#6F6CCC"),
        Map.entry("minecraft:short_dry_grass", "#BA9C68"),
        Map.entry("minecraft:tall_dry_grass", "#BA9C68")
    );
    public static final Set<String> GRASS_LIKE = Set.of("minecraft:bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass");

    private BackportBlocks() {
    }

    public static Set<Block> resolve(final String... ids) {
        final Set<Block> result = new HashSet<>();
        for (final String id : ids) {
            final ResourceLocation key = ResourceLocation.parse(id);
            BuiltInRegistries.BLOCK.getOptional(key).ifPresent(result::add);
            if (key.getNamespace().equals("minecraft")) {
                BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("vanillabackport", key.getPath())).ifPresent(result::add);
            }
        }
        return Set.copyOf(result);
    }
}
