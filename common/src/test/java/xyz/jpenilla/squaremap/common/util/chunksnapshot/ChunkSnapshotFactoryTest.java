package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSnapshotFactoryTest {
    private static SectionDecoder decoder;
    private static DimensionType dimension;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        final var registries = VanillaRegistries.createLookup();
        final MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        decoder = new SectionDecoder(biomes);
        dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
    }

    @ParameterizedTest
    @CsvSource({"-64,384,false", "-64,384,true", "0,256,false", "0,256,true"})
    void reconstructsMissingAndInvalidHeightmaps(final int minY, final int height, final boolean invalid) {
        final CompoundTag saved = new CompoundTag();
        saved.putInt("xPos", -11);
        saved.putInt("zPos", 7);
        final ListTag sections = new ListTag();
        for (int y = minY; y < minY + height; y += 16) {
            final PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            if (y == minY + height - 16) states.set(0, 15, 0, Blocks.STONE.defaultBlockState());
            if (y == minY + 16) states.set(3, 1, 4, Blocks.WATER.defaultBlockState());
            final CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) (y >> 4));
            section.put("block_states", PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState()).encodeStart(NbtOps.INSTANCE, states).getOrThrow());
            final CompoundTag biomes = new CompoundTag();
            final ListTag palette = new ListTag();
            palette.add(StringTag.valueOf("minecraft:plains"));
            biomes.put("palette", palette);
            section.put("biomes", biomes);
            sections.add(section);
        }
        saved.put("sections", sections);
        if (invalid) {
            final CompoundTag heights = new CompoundTag();
            heights.putLongArray("WORLD_SURFACE", new long[10]);
            saved.put("Heightmaps", heights);
        }
        final ChunkSnapshot snapshot = ChunkSnapshotFactory.snapshotFromChunkData(LevelHeightAccessor.create(minY, height), dimension, decoder, saved);
        assertEquals(minY + height - 1, snapshot.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        assertEquals(minY + 17, snapshot.getHeight(Heightmap.Types.WORLD_SURFACE, 3, 4));
        assertEquals(minY - 1, snapshot.getHeight(Heightmap.Types.WORLD_SURFACE, 5, 5));
        assertEquals(Blocks.STONE.defaultBlockState(), snapshot.getBlockState(new BlockPos(-176, minY + height - 1, 112)));
        assertTrue(snapshot.sectionEmpty(0));
        assertFalse(snapshot.sectionEmpty(1));
        assertNotNull(snapshot.uniformBiome(minY));
        assertEquals(-11, snapshot.pos().x);
        assertTrue(ChunkSnapshotFactory.chunkPosMatches(saved, -11, 7));
        assertFalse(ChunkSnapshotFactory.chunkPosMatches(saved, -12, 7));
    }
}
