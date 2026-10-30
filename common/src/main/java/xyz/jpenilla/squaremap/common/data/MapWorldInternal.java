package xyz.jpenilla.squaremap.common.data;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.api.LayerProvider;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Registry;
import xyz.jpenilla.squaremap.api.WorldIdentifier;
import xyz.jpenilla.squaremap.common.LayerRegistry;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.ConfigManager;
import xyz.jpenilla.squaremap.common.config.WorldAdvanced;
import xyz.jpenilla.squaremap.common.config.WorldConfig;
import xyz.jpenilla.squaremap.common.layer.SpawnIconLayer;
import xyz.jpenilla.squaremap.common.layer.WorldBorderLayer;
import xyz.jpenilla.squaremap.common.render.RenderFactory;
import xyz.jpenilla.squaremap.common.render.RenderScheduler;
import xyz.jpenilla.squaremap.common.render.output.RegionImage;
import xyz.jpenilla.squaremap.common.render.output.RegionImageSaveQueue;
import xyz.jpenilla.squaremap.common.render.scanning.BiomeColorTables;
import xyz.jpenilla.squaremap.common.util.concurrent.BackgroundExecutor;
import xyz.jpenilla.squaremap.common.util.concurrent.Threads;
import xyz.jpenilla.squaremap.common.world.SnapshotRequests;
import xyz.jpenilla.squaremap.common.util.Colors;
import xyz.jpenilla.squaremap.common.util.FileUtil;
import xyz.jpenilla.squaremap.common.util.ImageIOExecutor;
import xyz.jpenilla.squaremap.common.util.Util;
import xyz.jpenilla.squaremap.common.httpd.EventStream;
import xyz.jpenilla.squaremap.common.httpd.JsonCache;
import xyz.jpenilla.squaremap.common.web.TileChangeLog;
import xyz.jpenilla.squaremap.common.coordinate.TileCoordinate;
import xyz.jpenilla.squaremap.common.visibilitylimit.VisibilityLimitImpl;

@DefaultQualifier(NonNull.class)
public abstract class MapWorldInternal implements MapWorld {
    private static final String DIRTY_CHUNKS_FILE_NAME = "dirty_chunks.json";
    private static final Map<WorldIdentifier, LayerRegistry> LAYER_REGISTRIES = new HashMap<>();

    private final ServerLevel level;
    private final WorldConfig worldConfig;
    private final WorldAdvanced advancedWorldConfig;
    private final Path dataPath;
    private final Path tilesPath;
    private final RegionImageSaveQueue imageSaveQueue;
    private final BackgroundExecutor backgroundExecutor;
    private final JsonCache jsonCache;
    private final TileChangeLog tileChangeLog;
    private final Set<TileCoordinate> pendingTileEvents = ConcurrentHashMap.newKeySet();
    private final SnapshotRequests snapshotRequests = new SnapshotRequests(1);
    private final TileStorage tileStorage;
    private final RenderScheduler renderScheduler;
    private final Set<ChunkCoordinate> modifiedChunks = ConcurrentHashMap.newKeySet();
    private final xyz.jpenilla.squaremap.common.render.scanning.BlockColors blockColors;
    private final BiomeColorTables biomeColorTables;
    private final VisibilityLimitImpl visibilityLimit;
    private volatile long lastReset = -1;

    protected MapWorldInternal(
        final ServerLevel level,
        final RenderFactory renderFactory,
        final DirectoryProvider directoryProvider,
        final ConfigManager configManager,
        final EventStream eventStream,
        final JsonCache jsonCache
    ) {
        this.level = level;
        this.jsonCache = jsonCache;

        this.worldConfig = configManager.worldConfig(this.level);
        this.advancedWorldConfig = configManager.worldAdvanced(this.level);

        this.blockColors = new xyz.jpenilla.squaremap.common.render.scanning.BlockColors(this.advancedWorldConfig);
        this.biomeColorTables = BiomeColorTables.create(this.level, this.advancedWorldConfig);

        this.dataPath = directoryProvider.getAndCreateDataDirectory(this.serverLevel());
        this.tilesPath = directoryProvider.getAndCreateTilesDirectory(this.serverLevel());
        this.tileStorage = new TileStorage(this.tilesPath, this.worldConfig.ZOOM_MAX);
        this.backgroundExecutor = new BackgroundExecutor(Threads.name("background", this.identifier()));
        this.tileChangeLog = TileChangeLog.create(jsonCache, this.tilesPath.resolve(TileChangeLog.FILE_NAME), this.backgroundExecutor,
            () -> this.publishTileEvents(eventStream));
        this.imageSaveQueue = RegionImageSaveQueue.create(level, this.worldConfig.IMAGE_SAVING, this.tilesPath, this.worldConfig.ZOOM_MAX, tile -> {
            this.pendingTileEvents.add(tile);
            this.tileChangeLog.record(tile);
        });

        this.layerRegistry(); // init the layer registry
        if (this.config().SPAWN_MARKER_ICON_ENABLED) {
            this.layerRegistry().register(SpawnIconLayer.KEY, new SpawnIconLayer(this));
        }
        if (this.config().WORLDBORDER_MARKER_ENABLED) {
            this.layerRegistry().register(WorldBorderLayer.KEY, new WorldBorderLayer(this));
        }

        this.visibilityLimit = new VisibilityLimitImpl(this);
        this.visibilityLimit.load(this.config().VISIBILITY_LIMITS);

        this.deserializeDirtyChunks();

        this.renderScheduler = RenderScheduler.create(this, renderFactory);
    }

    @Override
    public Registry<LayerProvider> layerRegistry() {
        return LAYER_REGISTRIES.computeIfAbsent(this.identifier(), $ -> new LayerRegistry());
    }

    @Override
    public WorldIdentifier identifier() {
        return Util.worldIdentifier(this.level);
    }

    public RenderScheduler renderScheduler() {
        return this.renderScheduler;
    }

    public RenderScheduler renderManager() {
        return this.renderScheduler;
    }

    public SnapshotRequests snapshotRequests() {
        return this.snapshotRequests;
    }

    public BackgroundExecutor backgroundExecutor() {
        return this.backgroundExecutor;
    }

    public void publishWebJson(final String fileName, final Object data) {
        this.backgroundExecutor.execute(() -> this.jsonCache.put(this.tilesPath.resolve(fileName), Util.gson().toJson(data)));
    }

    private void publishTileEvents(final EventStream stream) {
        final List<Map<String, Integer>> changed = new java.util.ArrayList<>();
        for (final TileCoordinate tile : this.pendingTileEvents) {
            if (this.pendingTileEvents.remove(tile)) changed.add(Map.of("z", tile.zoom(), "x", tile.x(), "y", tile.z()));
        }
        if (!changed.isEmpty()) stream.publish("tiles-invalidated", Util.gson().toJson(Map.of("world", Util.levelWebName(this.level), "tiles", changed)), "base", Util.levelWebName(this.level));
    }

    public Path dataPath() {
        return this.dataPath;
    }

    /**
     * Get the map visibility limit of the world. Only these regions are drawn,
     * even if more chunks exist on disk.
     *
     * @return The visibility limit.
     */
    //@Override
    public VisibilityLimitImpl visibilityLimit() {
        return this.visibilityLimit;
    }

    public BiomeColorTables biomeColorTables() {
        return this.biomeColorTables;
    }

    public xyz.jpenilla.squaremap.common.render.scanning.BlockColors blockColors() {
        return this.blockColors;
    }

    public WorldConfig config() {
        return this.worldConfig;
    }

    public WorldAdvanced advanced() {
        return this.advancedWorldConfig;
    }

    public ServerLevel serverLevel() {
        return this.level;
    }

    public Path tilesPath() {
        return this.tilesPath;
    }

    @SuppressWarnings("ConstantConditions") // params for getMapColor are never used, check on mc update
    public int getMapColor(final BlockState state) {
        return this.blockColors.color(state);
    }

    public void saveImage(final RegionImage image) throws InterruptedException {
        this.imageSaveQueue.saveImage(image);
    }

    public TileStorage tileStorage() {
        return this.tileStorage;
    }

    public CompletableFuture<TileStorage.Stats> compactTiles(final boolean migrate) {
        return this.imageSaveQueue.compact(migrate);
    }

    public void chunkModified(final ChunkCoordinate coord) {
        if (!this.config().BACKGROUND_RENDER.enabled) {
            return;
        }
        if (!this.visibilityLimit().shouldRenderChunk(coord)) {
            return;
        }
        this.modifiedChunks.add(coord);
    }

    public boolean hasModifiedChunks() {
        return !this.modifiedChunks.isEmpty();
    }

    public ChunkCoordinate nextModifiedChunk() {
        final Iterator<ChunkCoordinate> it = this.modifiedChunks.iterator();
        final ChunkCoordinate coord = it.next();
        it.remove();
        return coord;
    }

    public void shutdown() {
        if (this.layerRegistry().hasEntry(SpawnIconLayer.KEY)) {
            this.layerRegistry().unregister(SpawnIconLayer.KEY);
        }
        if (this.layerRegistry().hasEntry(WorldBorderLayer.KEY)) {
            this.layerRegistry().unregister(WorldBorderLayer.KEY);
        }
        this.renderScheduler.shutdown();
        this.imageSaveQueue.shutdown();
        this.tileChangeLog.close();
        this.backgroundExecutor.shutdown();
        this.serializeDirtyChunks();
    }

    private void serializeDirtyChunks() {
        final Path file = this.dataPath.resolve(DIRTY_CHUNKS_FILE_NAME);
        if (this.modifiedChunks.size() > 200000) { // ~6MB
            Logging.logger().warn("Map for world '{}' has a large amount ({}) of chunks queued for background render! If this notice appears frequently, consider adjusting the background render and or update trigger settings.", this.identifier().asString(), this.modifiedChunks.size());
        }
        try {
            FileUtil.atomicWrite(file, tmp -> Files.writeString(tmp, Util.gson().toJson(this.modifiedChunks)));
        } catch (final IOException ex) {
            Logging.logger().warn("Failed to serialize dirty chunks for world '{}' to file '{}'", this.identifier().asString(), file, ex);
        }
    }

    private void deserializeDirtyChunks() {
        final Path file = this.dataPath.resolve(DIRTY_CHUNKS_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return;
        }
        final List<ChunkCoordinate> deserialized;
        try (final BufferedReader reader = Files.newBufferedReader(file)) {
            deserialized = Util.gson().fromJson(reader, new TypeToken<List<ChunkCoordinate>>() {}.getType());
        } catch (final JsonIOException | JsonSyntaxException | IOException ex) {
            Logging.logger().warn("Failed to deserialize dirty chunks for world '{}' from file '{}'", this.identifier().asString(), file, ex);
            return;
        }
        if (deserialized == null) {
            Logging.logger().warn("Failed to deserialize dirty chunks for world '{}' from file '{}' (null result, file is corrupted or empty?)", this.identifier().asString(), file);
            return;
        }
        this.modifiedChunks.addAll(deserialized);
    }

    public void didReset() {
        this.lastReset = System.currentTimeMillis();
    }

    public long lastReset() {
        return this.lastReset;
    }

    public interface Factory {
        MapWorldInternal create(ServerLevel level);
    }
}
