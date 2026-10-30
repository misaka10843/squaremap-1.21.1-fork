package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;

@DefaultQualifier(NonNull.class)
public final class ChunkSnapshotFactory {
    private ChunkSnapshotFactory() {
    }

    /**
     * Captures a live chunk without retaining mutable block-state, biome, or heightmap data.
     *
     * <p>Must be called on the owning server thread while the chunk is safe to access.
     * The caller is responsible for completing any required chunk upgrades first.</p>
     *
     * @param level the level containing the chunk
     * @param chunk the chunk to snapshot
     * @return the captured snapshot
     * @throws IllegalStateException if the required world-surface heightmap is missing
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ChunkSnapshot snapshotLiveChunk(final Level level, final ChunkAccess chunk) {
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(chunk.getMinBuildHeight(), chunk.getHeight());
        final int sectionCount = heightAccessor.getSectionsCount();
        final LevelChunkSection[] sections = chunk.getSections();
        final SectionData<BlockState>[] states = new SectionData[sectionCount];
        final SectionData<Holder<Biome>>[] biomes = new SectionData[sectionCount];

        final boolean[] empty = new boolean[sectionCount];
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) {
            throw new IllegalStateException("Expected WORLD_SURFACE heightmap to be present, but it wasn't! " + chunk.getPos());
        }
        final Map<Heightmap.Types, HeightmapSnapshot> heightmaps = new EnumMap<>(ChunkSnapshotImpl.EMPTY_HEIGHTMAPS);
        heightmaps.put(Heightmap.Types.WORLD_SURFACE, new HeightmapSnapshot(chunk, heightAccessor, Heightmap.Types.WORLD_SURFACE));

        for (int i = 0; i < sectionCount; i++) {
            final boolean sectionEmpty = sections[i].hasOnlyAir();
            empty[i] = sectionEmpty;

            if (sectionEmpty) {
                states[i] = SectionData.Packed.single(Blocks.AIR.defaultBlockState());
            } else {
                states[i] = SectionData.of(sections[i].getStates().copy(), 4);
            }

            biomes[i] = SectionData.of(((PalettedContainer<Holder<Biome>>) sections[i].getBiomes()).copy(), 2);
        }

        return new ChunkSnapshotImpl(
            heightAccessor,
            states,
            biomes,
            uniformBiomes(biomes),
            heightmaps,
            empty,
            level.dimensionType(),
            chunk.getPos()
        );
    }

    /**
     * Decodes a snapshot directly from saved chunk data without loading a live chunk.
     *
     * <p>The caller must supply NBT already upgraded to the current data version and classified
     * as {@link ChunkSnapshotEligibility#ELIGIBLE}. The data is only read, so it may be shared
     * with other readers, but must not be mutated afterward; the snapshot keeps its data arrays.
     * This method does not check generation status or complete retrogen and may run off-thread.</p>
     *
     * @param levelHeight the level's vertical bounds
     * @param dimensionType the level's dimension type
     * @param sectionDecoder the level's section decoder
     * @param chunkData the upgraded, snapshot-ready chunk data
     * @return the decoded snapshot
     * @throws IllegalArgumentException if a section palette cannot be decoded
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ChunkSnapshot snapshotFromChunkData(
        final LevelHeightAccessor levelHeight,
        final DimensionType dimensionType,
        final SectionDecoder sectionDecoder,
        final CompoundTag chunkData
    ) {
        final ChunkPos chunkPos = readChunkPos(chunkData);
        final ListTag sectionTags = chunkData.getList("sections", Tag.TAG_COMPOUND);
        final int sectionCount = levelHeight.getSectionsCount();

        final SectionData<BlockState>[] states = new SectionData[sectionCount];
        final SectionData<Holder<Biome>>[] biomes = new SectionData[sectionCount];
        final boolean[] empty = new boolean[sectionCount];
        Arrays.fill(states, SectionData.Packed.single(Blocks.AIR.defaultBlockState()));
        Arrays.fill(biomes, SectionData.Packed.single(sectionDecoder.defaultBiome()));
        Arrays.fill(empty, true);

        for (int i = 0; i < sectionTags.size(); ++i) {
                final CompoundTag sectionTag = sectionTags.getCompound(i);
                final int y = sectionTag.getByte("Y");
                final int index = y - levelHeight.getMinSection();
                if (index >= 0 && index < sectionCount) {
                    final SectionData<BlockState> blocks = sectionTag.contains("block_states", Tag.TAG_COMPOUND)
                        ? sectionDecoder.blockStates(sectionTag.getCompound("block_states"))
                            .promotePartial(msg -> logSectionDecodeErrors(chunkPos, y, msg))
                            .getOrThrow(IllegalArgumentException::new)
                        : SectionData.Packed.single(Blocks.AIR.defaultBlockState());
                    final SectionData<Holder<Biome>> sectionBiomes = sectionTag.contains("biomes", Tag.TAG_COMPOUND)
                        ? sectionDecoder.biomes(sectionTag.getCompound("biomes"))
                            .promotePartial(msg -> logSectionDecodeErrors(chunkPos, y, msg))
                            .getOrThrow(IllegalArgumentException::new)
                        : SectionData.Packed.single(sectionDecoder.defaultBiome());
                    states[index] = blocks;
                    empty[index] = !blocks.maybeHas(state -> !state.isAir());
                    biomes[index] = sectionBiomes;
                }
        }

        final Map<Heightmap.Types, HeightmapSnapshot> heightmaps = readOrComputeHeightmaps(
            levelHeight, chunkData, states, empty);

        return new ChunkSnapshotImpl(
            levelHeight,
            states,
            biomes,
            uniformBiomes(biomes),
            heightmaps,
            empty,
            dimensionType,
            chunkPos
        );
    }

    /**
     * Returns chunk data that is safe to pass to an in-place chunk tag upgrade.
     *
     * <p>Chunk data read without copying may be shared with other readers. Upgrading mutates
     * outdated data, so it is copied first; current data is not touched by the upgrade and is
     * returned as-is.</p>
     *
     * @param chunkData the possibly shared chunk data
     * @return chunk data the caller may upgrade
     */
    public static CompoundTag ownedForUpgrade(final CompoundTag chunkData) {
        if (NbtUtils.getDataVersion(chunkData, -1) < SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            return chunkData.copy();
        }
        return chunkData;
    }

    public static boolean chunkPosMatches(final CompoundTag chunkData, final int x, final int z) {
        final int cx = chunkData.getInt("xPos");
        if (cx != x) return false;
        final int cz = chunkData.getInt("zPos");
        return cz == z;
    }

    public static ChunkPos readChunkPos(final CompoundTag chunkData) {
        return new ChunkPos(
            chunkData.getInt("xPos"),
            chunkData.getInt("zPos")
        );
    }

    private static void logSectionDecodeErrors(final ChunkPos chunkPos, final int sectionY, final String message) {
        // Same message as vanilla's SerializableChunkData.logErrors.
        Logging.logger().error("Recoverable errors when loading section [{}, {}, {}]: {}", chunkPos.x, sectionY, chunkPos.z, message);
    }

    private static Map<Heightmap.Types, HeightmapSnapshot> readOrComputeHeightmaps(
        final LevelHeightAccessor levelHeight,
        final CompoundTag chunkData,
        final SectionData<BlockState>[] states,
        final boolean[] empty
    ) {
        final int bits = Mth.ceillog2(levelHeight.getHeight() + 1);
        final int valuesPerLong = Long.SIZE / bits;
        final int expectedLength = (256 + valuesPerLong - 1) / valuesPerLong;
        final long[] saved = chunkData.getCompound("Heightmaps").getLongArray(Heightmap.Types.WORLD_SURFACE.getSerializationKey());
        final HeightmapSnapshot worldSurface = saved.length == expectedLength
            ? new HeightmapSnapshot(saved, levelHeight)
            : HeightmapSnapshot.computeWorldSurface(levelHeight, states, empty);
        final Map<Heightmap.Types, HeightmapSnapshot> heightmaps = new EnumMap<>(Heightmap.Types.class);
        heightmaps.put(Heightmap.Types.WORLD_SURFACE, worldSurface);
        return heightmaps;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Holder<Biome>[] uniformBiomes(final SectionData<Holder<Biome>>[] biomes) {
        final @Nullable Holder<Biome>[] uniform = new Holder[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            final Holder<Biome> first = biomes[i].get(0);
            // Checks palette entries only, so a palette with unused entries reads as mixed.
            if (!biomes[i].maybeHas(biome -> biome != first)) {
                uniform[i] = first;
            }
        }
        return uniform;
    }
}
