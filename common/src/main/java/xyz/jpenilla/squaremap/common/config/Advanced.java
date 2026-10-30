package xyz.jpenilla.squaremap.common.config;

import io.leangen.geantyref.TypeToken;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.util.ReflectionUtil;
import xyz.jpenilla.squaremap.common.util.BackportBlocks;

@SuppressWarnings("unused")
public final class Advanced extends AbstractConfig {
    private static final int LATEST_VERSION = 4;

    Advanced(final DirectoryProvider directoryProvider) {
        super(directoryProvider.dataDirectory(), Advanced.class, "advanced.yml", LATEST_VERSION);
    }

    @Override
    protected void addVersions(ConfigurationTransformation.VersionedBuilder versionedBuilder) {
        final ConfigurationTransformation oneToTwo = ConfigurationTransformation.builder()
            .addAction(NodePath.path("world-settings", "default", "color-overrides"), (path, node) -> {
                final TypeToken<Map<String, String>> type = new TypeToken<>() {};

                final ConfigurationNode foliageNode = node.node("biomes", "foliage");
                final Map<String, String> foliageMap = foliageNode.get(type, new HashMap<>());
                foliageMap.put("minecraft:mangrove_swamp", "#6f9623");
                foliageNode.set(type, foliageMap);

                final ConfigurationNode blocksNode = node.node("blocks");
                final Map<String, String> blocksMap = blocksNode.get(type, new HashMap<>());
                blocksMap.put("minecraft:pink_petals", "#FFB4DB");
                blocksNode.set(type, blocksMap);
                return null;
            })
            .build();
        final ConfigurationTransformation twoToThree = ConfigurationTransformation.builder()
            .addAction(NodePath.path("world-settings"), Transformations.eachMapChild(worldSection -> {
                Transformations.applyMapKeyOrListValueRenames(
                    List.of(
                        worldSection.node("invisible-blocks"),
                        worldSection.node("iterate-up-base-blocks"),
                        worldSection.node("color-overrides", "blocks")
                    ),
                    Map.of(
                        Transformations.maybeMinecraft("grass"), "minecraft:short_grass"
                    )
                );
            }))
            .build();

        versionedBuilder.addVersion(2, oneToTwo);
        versionedBuilder.addVersion(3, twoToThree);
        versionedBuilder.addVersion(4, ConfigurationTransformation.builder()
            .addAction(NodePath.path("world-settings"), Transformations.eachMapChild(Advanced::addBackportDefaults)).build());
    }

    static void addBackportDefaults(final ConfigurationNode world) throws org.spongepowered.configurate.serialize.SerializationException {
        final ConfigurationNode blocks = world.node("color-overrides", "blocks");
        if (!blocks.virtual()) {
            final TypeToken<Map<String, String>> type = new TypeToken<>() {};
            final Map<String, String> colors = new HashMap<>(blocks.get(type, Map.of()));
            BackportBlocks.COLORS.forEach(colors::putIfAbsent);
            blocks.set(type, colors);
        }
        final ConfigurationNode hidden = world.node("invisible-blocks");
        if (!hidden.virtual()) {
            final List<String> names = new ArrayList<>(hidden.getList(String.class, List.of()));
            if (names.contains("minecraft:short_grass") || names.contains("short_grass")) {
                for (final String name : BackportBlocks.GRASS_LIKE) if (!names.contains(name)) names.add(name);
                hidden.setList(String.class, names);
            }
        }
    }

    static Advanced config;

    public static void reload(final DirectoryProvider directoryProvider) {
        config = new Advanced(directoryProvider);
        config.readConfig(Advanced.class, null);

        // todo - replace hack
        final Class<?> bukkitAdvancedClass = ReflectionUtil.findClass("xyz.jpenilla.squaremap.paper.config.PaperAdvanced");
        if (bukkitAdvancedClass != null) {
            config.readConfig(bukkitAdvancedClass, null);
        }
        final Class<?> spongeAdvancedClass = ReflectionUtil.findClass("xyz.jpenilla.squaremap.sponge.config.SpongeAdvanced");
        if (spongeAdvancedClass != null) {
            config.readConfig(spongeAdvancedClass, null);
        }
    }

    public static Advanced config() {
        return config;
    }
}
