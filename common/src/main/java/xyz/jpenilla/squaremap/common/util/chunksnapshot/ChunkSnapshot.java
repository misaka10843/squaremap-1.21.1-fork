package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface ChunkSnapshot extends LevelHeightAccessor, BiomeManager.NoiseBiomeSource {
    BlockState getBlockState(BlockPos pos);
    FluidState getFluidState(BlockPos pos);
    int getHeight(Heightmap.Types type, int x, int z);
    DimensionType dimensionType();
    ChunkPos pos();
    boolean sectionEmpty(int sectionIndex);
    @Nullable Holder<Biome> uniformBiome(int y);

    static ChunkSnapshot snapshot(final Level level, final ChunkAccess chunk, final boolean biomesOnly) {
        return ChunkSnapshotFactory.snapshotLiveChunk(level, chunk);
    }
}
