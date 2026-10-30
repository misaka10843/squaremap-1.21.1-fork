package xyz.jpenilla.squaremap.common.util.chunksnapshot;

import ca.spottedleaf.moonrise.common.util.ChunkSystem;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.libs.ca.spottedleaf.concurrentutil.util.Priority;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.nbt.NbtOps;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.util.ChunkMapAccess;

@DefaultQualifier(NonNull.class)
record VanillaChunkSnapshotProvider(ServerLevel level, boolean moonrise, SectionDecoder sectionDecoder) implements ChunkSnapshotProvider {
    private static final ResourceLocation FULL = BuiltInRegistries.CHUNK_STATUS.getKey(ChunkStatus.FULL);
    private static final TicketType<Long> SNAPSHOT_TICKET = TicketType.create("squaremap_snapshot", Long::compare);
    private static final AtomicLong NEXT_REQUEST = new AtomicLong();
    private static final Executor LOAD_DISPATCHER = task -> Thread.ofVirtual().name("squaremap-chunk-load-dispatch").start(task);

    @Override
    public CompletableFuture<@Nullable ChunkSnapshot> asyncSnapshot(final int x, final int z) {
        if (this.moonrise) {
            return this.moonriseAsyncSnapshot(x, z);
        }
        return CompletableFuture.supplyAsync(() -> this.vanillaAsyncSnapshot(x, z), this.level.getServer())
            .thenCompose(future -> future);
    }

    private CompletableFuture<@Nullable ChunkSnapshot> moonriseAsyncSnapshot(final int x, final int z) {
        final var scheduler = ((ChunkSystemServerLevel) this.level).moonrise$getChunkTaskScheduler();
        final var lock = scheduler.schedulingLockArea.lock(x, z);
        try {
            if (scheduler.chunkHolderManager.getChunkHolder(x, z) == null) {
                final CompletableFuture<@Nullable CompoundTag> raw = new CompletableFuture<>();
                MoonriseRegionFileIO.loadDataAsync(this.level, x, z, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                    MoonriseSnapshotIO.callback(raw), false, Priority.NORMAL);
                final LevelHeightAccessor bounds = LevelHeightAccessor.create(this.level.getMinBuildHeight(), this.level.getHeight());
                return raw.thenComposeAsync(tag -> this.snapshotSavedData(x, z, bounds, tag), net.minecraft.Util.backgroundExecutor());
            }
        } finally {
            scheduler.schedulingLockArea.unlock(lock);
        }
        return this.moonriseChunkSystemSnapshot(x, z, ChunkStatus.EMPTY);
    }

    private CompletableFuture<@Nullable ChunkSnapshot> moonriseChunkSystemSnapshot(final int x, final int z, final ChunkStatus requested) {
        return CompletableFuture.supplyAsync(() -> {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ChunkMapAccess chunkMap = (ChunkMapAccess) this.level.getChunkSource().chunkMap;

            final ChunkHolder visibleChunk = chunkMap.squaremap$getVisibleChunkIfPresent(chunkPos.toLong());
            if (visibleChunk != null && requested == ChunkStatus.EMPTY) {
                final @Nullable ChunkAccess chunk = fullIfPresent(visibleChunk);
                if (chunk != null) {
                    return this.liveSnapshot(x, z, chunk);
                }
            }

            final CompletableFuture<@Nullable ChunkSnapshot> load = new CompletableFuture<>();
            ChunkSystem.scheduleChunkLoad(
                this.level,
                x,
                z,
                requested,
                true,
                Priority.NORMAL,
                chunk -> {
                    try {
                        final @Nullable ChunkAccess unwrap = unwrap(chunk);
                        if (unwrap == null) {
                            load.complete(null);
                        } else if (ChunkSnapshotEligibility.get(unwrap.getPersistedStatus(), unwrap.getBelowZeroRetrogen()) == ChunkSnapshotEligibility.NEEDS_RETROGEN) {
                            if (requested == ChunkStatus.FULL) {
                                throw new IllegalStateException("Retrogen did not finish: " + unwrap.getPos());
                            }
                            this.moonriseChunkSystemSnapshot(x, z, ChunkStatus.FULL).whenComplete((snapshot, error) -> {
                                if (error != null) load.completeExceptionally(error);
                                else load.complete(snapshot);
                            });
                        } else {
                            load.complete(ChunkSnapshotFactory.snapshotLiveChunk(this.level, unwrap));
                        }
                    } catch (final Throwable error) {
                        load.completeExceptionally(error);
                    }
                }
            );
            return load;
        }, this.level.getServer()).thenCompose(future -> future);
    }

    private CompletableFuture<@Nullable ChunkSnapshot> vanillaAsyncSnapshot(final int x, final int z) {
        final ServerLevel level = this.level;
        final ChunkPos chunkPos = new ChunkPos(x, z);
        final ChunkMapAccess chunkMap = (ChunkMapAccess) level.getChunkSource().chunkMap;

        final ChunkHolder visibleChunk = chunkMap.squaremap$getVisibleChunkIfPresent(chunkPos.toLong());
        if (visibleChunk != null) {
            final @Nullable ChunkAccess chunk = fullIfPresent(visibleChunk);
            if (chunk != null) {
                return this.liveSnapshot(x, z, chunk);
            }
        }

        final ChunkHolder unloadingChunk = chunkMap.squaremap$pendingUnloads().get(chunkPos.toLong());
        if (unloadingChunk != null) {
            final @Nullable ChunkAccess chunk = fullIfPresent(unloadingChunk);
            if (chunk != null) {
                return this.liveSnapshot(x, z, chunk);
            }
        }

        // Queue the disk read after checking holders on main, preserving ordering with unload saves.
        // Never join disk IO on the server thread.
        final LevelHeightAccessor bounds = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
        return chunkMap.squaremap$readChunk(chunkPos).thenComposeAsync(
            maybeTag -> this.snapshotSavedData(x, z, bounds, maybeTag.orElse(null)), net.minecraft.Util.backgroundExecutor());
    }

    private CompletableFuture<@Nullable ChunkSnapshot> liveSnapshot(final int x, final int z, final ChunkAccess chunk) {
        if (ChunkSnapshotEligibility.get(chunk.getPersistedStatus(), chunk.getBelowZeroRetrogen()) == ChunkSnapshotEligibility.NEEDS_RETROGEN) {
            return this.loadSnapshot(x, z, ChunkStatus.FULL);
        }
        return CompletableFuture.completedFuture(ChunkSnapshotFactory.snapshotLiveChunk(this.level, chunk));
    }

    private CompletableFuture<@Nullable ChunkSnapshot> snapshotSavedData(final int x, final int z, final LevelHeightAccessor bounds, final @Nullable CompoundTag raw) {
        if (raw == null) return CompletableFuture.completedFuture(null);
        final CompoundTag tag = this.level.getChunkSource().chunkMap.upgradeChunkTag(
            this.level.dimension(), this.level::getDataStorage, ChunkSnapshotFactory.ownedForUpgrade(raw),
            this.level.getChunkSource().getGenerator().getTypeNameForDataFixer());
        final @Nullable ResourceLocation statusKey = ResourceLocation.tryParse(tag.getString("Status"));
        final ChunkStatus status = statusKey == null ? ChunkStatus.EMPTY : BuiltInRegistries.CHUNK_STATUS.getOptional(statusKey).orElse(ChunkStatus.EMPTY);
        final @Nullable BelowZeroRetrogen retrogen = tag.contains("below_zero_retrogen", Tag.TAG_COMPOUND)
            ? BelowZeroRetrogen.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("below_zero_retrogen")).getOrThrow() : null;
        return switch (ChunkSnapshotEligibility.get(status, retrogen)) {
            case INELIGIBLE -> CompletableFuture.completedFuture(null);
            case NEEDS_RETROGEN -> this.loadSnapshot(x, z, ChunkStatus.FULL);
            case ELIGIBLE -> ChunkSnapshotFactory.chunkPosMatches(tag, x, z)
                ? CompletableFuture.completedFuture(ChunkSnapshotFactory.snapshotFromChunkData(bounds, this.level.dimensionType(), this.sectionDecoder, tag))
                : this.loadSnapshot(x, z, ChunkStatus.EMPTY);
        };
    }

    private CompletableFuture<@Nullable ChunkSnapshot> loadSnapshot(final int x, final int z, final ChunkStatus requested) {
        if (this.moonrise) return this.moonriseChunkSystemSnapshot(x, z, requested);
        return CompletableFuture.supplyAsync(() -> {
            final ServerLevel level = this.level;
            final ChunkPos chunkPos = new ChunkPos(x, z);

            final long request = NEXT_REQUEST.incrementAndGet();
            final var source = level.getChunkSource();
            // Region ticket API converts radius to level as FULL_LEVEL - radius.
            // An EMPTY-level ticket holds the chunk without requesting terrain generation.
            final int radius = ChunkLevel.byStatus(ChunkStatus.FULL) - ChunkLevel.byStatus(requested);
            source.addRegionTicket(SNAPSHOT_TICKET, chunkPos, radius, request);
            // getChunkFuture itself managed-blocks when called on main. Dispatch off-main,
            // then copy the live chunk on main while our non-expiring ticket still holds it.
            return CompletableFuture.supplyAsync(() -> source.getChunkFuture(x, z, requested, true), LOAD_DISPATCHER)
                .thenCompose(future -> future)
                .thenComposeAsync(result -> {
                    final @Nullable ChunkAccess chunk = unwrap(result.orElse(null));
                    if (chunk == null) return CompletableFuture.<@Nullable ChunkSnapshot>completedFuture(null);
                    if (requested == ChunkStatus.FULL
                        && ChunkSnapshotEligibility.get(chunk.getPersistedStatus(), chunk.getBelowZeroRetrogen()) != ChunkSnapshotEligibility.ELIGIBLE) {
                        throw new IllegalStateException("Chunk upgrade did not finish: " + chunk.getPos());
                    }
                    return this.liveSnapshot(x, z, chunk);
                }, level.getServer())
                .whenCompleteAsync((result, error) -> source.removeRegionTicket(SNAPSHOT_TICKET, chunkPos, radius, request), level.getServer());
        }, this.level.getServer()).thenCompose(future -> future);
    }

    private static boolean isFullStatus(final CompoundTag chunkTag) {
        return FULL.equals(ResourceLocation.tryParse(chunkTag.getString("Status")));
    }

    private static @Nullable ChunkAccess fullIfPresent(final ChunkHolder chunkHolder) {
        return unwrap(chunkHolder.getLatestChunk());
    }

    private static @Nullable ChunkAccess unwrap(@Nullable ChunkAccess chunk) {
        if (chunk == null) {
            return null;
        }
        if (chunk instanceof ImposterProtoChunk imposter) {
            chunk = imposter.getWrapped();
        }
        if (!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL) && !preHeightChangeFullChunk(chunk)) {
            return null;
        }
        return chunk;
    }

    private static boolean preHeightChangeFullChunk(final ChunkAccess chunk) {
        return chunk.getBelowZeroRetrogen() != null && chunk.getBelowZeroRetrogen().targetStatus().isOrAfter(ChunkStatus.SPAWN);
    }

    private static boolean preHeightChangeFullChunk(final CompoundTag chunkTag) {
        final CompoundTag belowZeroRetrogen = chunkTag.getCompound("below_zero_retrogen");
        if (belowZeroRetrogen.isEmpty()) {
            return false;
        }
        final String targetStatusStr = belowZeroRetrogen.getString("target_status");
        if (targetStatusStr.isEmpty()) {
            return false;
        }
        final @Nullable ResourceLocation targetStatus = ResourceLocation.tryParse(targetStatusStr);
        if (targetStatus == null) {
            return false;
        }
        return BuiltInRegistries.CHUNK_STATUS.get(targetStatus).isOrAfter(ChunkStatus.SPAWN);
    }
}
