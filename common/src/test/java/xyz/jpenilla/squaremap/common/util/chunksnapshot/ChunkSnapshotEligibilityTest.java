package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSnapshotEligibilityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void incompleteChunksAreNeverRequestedAsFullJustForMapping() {
        assertEquals(ChunkSnapshotEligibility.INELIGIBLE, ChunkSnapshotEligibility.get(ChunkStatus.EMPTY, null));
        assertEquals(ChunkSnapshotEligibility.INELIGIBLE, ChunkSnapshotEligibility.get(ChunkStatus.SPAWN, null));
        assertEquals(ChunkSnapshotEligibility.ELIGIBLE, ChunkSnapshotEligibility.get(ChunkStatus.FULL, null));
    }

    @Test
    void oldFullChunksFinishRetrogenBeforeSnapshotting() {
        final CompoundTag saved = new CompoundTag();
        saved.putString("target_status", "minecraft:spawn");
        saved.putLongArray("missing_bedrock", new long[0]);
        final BelowZeroRetrogen retrogen = BelowZeroRetrogen.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(ChunkSnapshotEligibility.NEEDS_RETROGEN, ChunkSnapshotEligibility.get(ChunkStatus.EMPTY, retrogen));
        assertEquals(ChunkSnapshotEligibility.NEEDS_RETROGEN, ChunkSnapshotEligibility.get(ChunkStatus.FULL, retrogen));
    }

    @Test
    void vanillaProviderLoadsWhenOptionalMoonriseIsAbsent() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("ca.spottedleaf.moonrise.common.util.ChunkSystem"));
        assertNotNull(Class.forName("xyz.jpenilla.squaremap.common.util.chunksnapshot.VanillaChunkSnapshotProvider"));
    }
}
