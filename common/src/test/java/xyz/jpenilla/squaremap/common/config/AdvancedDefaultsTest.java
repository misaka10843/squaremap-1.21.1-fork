package xyz.jpenilla.squaremap.common.config;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedDefaultsTest {
    @Test
    void migrationKeepsUserColorsAndAddsGrassRulesOnce() throws Exception {
        final var node = BasicConfigurationNode.root();
        node.node("color-overrides", "blocks", "minecraft:pale_oak_leaves").set("#123456");
        node.node("invisible-blocks").setList(String.class, List.of("minecraft:short_grass"));
        Advanced.addBackportDefaults(node);
        Advanced.addBackportDefaults(node);
        assertEquals("#123456", node.node("color-overrides", "blocks", "minecraft:pale_oak_leaves").getString());
        assertEquals("#EDD575", node.node("color-overrides", "blocks", "minecraft:wildflowers").getString());
        final List<String> hidden = node.node("invisible-blocks").getList(String.class);
        assertEquals(4, hidden.size());
        assertTrue(hidden.contains("minecraft:bush"));
    }

    @Test
    void migrationPreservesVisibleGrassAndInheritedColorMaps() throws Exception {
        final var node = BasicConfigurationNode.root();
        node.node("invisible-blocks").setList(String.class, List.of("minecraft:fern"));
        Advanced.addBackportDefaults(node);
        assertEquals(List.of("minecraft:fern"), node.node("invisible-blocks").getList(String.class));
        assertTrue(node.node("color-overrides", "blocks").virtual());
    }
}
