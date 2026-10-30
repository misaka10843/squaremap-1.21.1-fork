package xyz.jpenilla.squaremap.forge.create;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBlock.SignalType;
import com.simibubi.create.content.trains.signal.SignalBlockEntity.SignalState;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.common.Logging;
import xyz.jpenilla.squaremap.common.config.Config;
import xyz.jpenilla.squaremap.common.httpd.JsonCache;
import xyz.jpenilla.squaremap.common.httpd.EventStream;
import xyz.jpenilla.squaremap.common.util.Util;

/**
 * Create railway overlay for squaremap.
 *
 * <p>Minecraft and Create objects are only read from the server thread. Static
 * topology extraction is split across ticks with a strict time budget. JSON
 * serialization and cache publication run on one daemon worker thread.</p>
 */
@DefaultQualifier(NonNull.class)
@Singleton
public final class CreateTrackMapIntegration {
    private static final String STATUS_PATH = "/tiles/create/status.json";
    private static final String NETWORK_PATH = "/tiles/create/network.json";
    private static final String DYNAMIC_PATH = "/tiles/create/dynamic.json";
    private static final String REFRESH_PATH = "/tiles/create/refresh.json";
    private static final String SIGNALS_PATH = "/tiles/create/signals.json";
    private static final String BLOCKS_PATH = "/tiles/create/blocks.json";
    private static final String TRAINS_PATH = "/tiles/create/trains.json";

    private final JsonCache jsonCache;
    private final EventStream eventStream;
    private final ExecutorService worker;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AtomicReference<DynamicPublishTask> pendingDynamic = new AtomicReference<>();
    private final AtomicBoolean dynamicWorkerScheduled = new AtomicBoolean();

    /* Accessed only from the Minecraft server thread unless marked volatile. */
    private volatile Map<UUID, SignalRef> signalRefs = Map.of();
    private volatile Set<UUID> knownSignalBlocks = Set.of();
    private volatile Map<String, Set<String>> dynamicWorlds = Map.of();
    private final Map<String, Set<String>> streamWorlds = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastStatusEventNanos;
    private @Nullable String lastStatusState;
    private volatile @Nullable String lastNetworkState;
    private volatile @Nullable StaticBuildJob staticBuildJob;
    private volatile boolean refreshRequested;
    private volatile boolean forceRefreshRequested;
    private volatile String requestedRefreshReason = "startup";
    private int ticks;
    private int observedRailwayVersion = Integer.MIN_VALUE;
    private volatile int publishedRailwayVersion = Integer.MIN_VALUE;
    private volatile long refreshSequence;
    private volatile long publishingSequence = -1L;
    private volatile double refreshProgress;
    private volatile String refreshReason = "idle";
    private volatile long lastStaticRefreshMillis;
    private volatile long nextStaticRefreshMillis;
    private volatile long lastDynamicRefreshMillis;
    private volatile long networkRevision;
    private volatile int publishedTrackCount;
    private volatile int publishedStationCount;
    private volatile int publishedSignalBlockCount;
    private volatile int publishedTrainCount;
    private volatile long lastStaticSliceMicros;
    private volatile long lastDynamicCaptureMicros;
    private volatile long maxMainThreadMicros;
    private volatile @Nullable String lastError;
    private long lastHandledWebRefreshAccess = Long.MIN_VALUE;
    private volatile long webRefreshCooldownUntilMillis;
    private volatile boolean enabled;
    private volatile boolean running;
    private volatile List<Map<String, Object>> lastPublishedSignals = List.of();
    private volatile List<Map<String, Object>> lastPublishedBlocks = List.of();
    private volatile List<Map<String, Object>> lastPublishedTrains = List.of();

    @Inject
    private CreateTrackMapIntegration(final JsonCache jsonCache, final EventStream eventStream) {
        this.jsonCache = jsonCache;
        this.eventStream = eventStream;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "squaremap-create-worker");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, throwable) ->
                Logging.logger().error("Uncaught exception in squaremap Create worker", throwable));
            return thread;
        });
    }

    public void start() {
        this.running = true;
        this.ticks = 0;
        this.jsonCache.putVolatile(REFRESH_PATH, "{\"accepted\":true}");
        this.applyEnabled(Config.CREATE_MAP_ENABLED);
    }

    public void stop() {
        this.running = false;
        this.lifecycleGeneration.incrementAndGet();
        this.staticBuildJob = null;
        this.pendingDynamic.set(null);
        // The singleton is reused by /squaremap reload. Keep the daemon worker
        // alive; generation checks prevent stale tasks from publishing.
        this.clearPublishedData(true);
    }

    public void setEnabled(final boolean enabled) {
        this.applyEnabled(enabled);
    }

    public boolean enabled() {
        return this.enabled;
    }

    public boolean refreshing() {
        return this.staticBuildJob != null || this.publishingSequence == this.refreshSequence;
    }

    public void requestRefresh(final String reason, final boolean force) {
        if (!this.enabled) {
            return;
        }
        this.refreshRequested = true;
        this.forceRefreshRequested |= force;
        this.requestedRefreshReason = reason;
        this.publishStatus();
    }

    public String statusText() {
        final String state = !this.enabled ? "disabled"
            : this.refreshing() ? "refreshing " + Math.round(this.refreshProgress * 100.0D) + "%"
            : this.hasRecentViewer() ? "active" : "sleeping (no web viewers)";
        return "Create integration: " + state
            + ", network=" + this.publishedRailwayVersion
            + ", tracks=" + this.publishedTrackCount
            + ", stations=" + this.publishedStationCount
            + ", blocks=" + this.publishedSignalBlockCount
            + ", trains=" + this.publishedTrainCount
            + ", main-thread=" + (Math.max(this.lastStaticSliceMicros, this.lastDynamicCaptureMicros) / 1000.0D) + "ms";
    }

    public void tick(final MinecraftServer server) {
        if (!this.running) {
            return;
        }
        if (this.enabled != Config.CREATE_MAP_ENABLED) {
            this.applyEnabled(Config.CREATE_MAP_ENABLED);
        }

        this.handleWebRefreshRequest();
        if (!this.enabled) {
            if (++this.ticks % 20 == 0) {
                this.publishStatus();
            }
            return;
        }

        try {
            final GlobalRailwayManager railways = Create.RAILWAYS;
            final long now = System.currentTimeMillis();
            final boolean recentViewer = this.hasRecentViewer();

            if (this.observedRailwayVersion != railways.version) {
                this.observedRailwayVersion = railways.version;
                this.requestRefresh("topology changed", false);
            }
            if (recentViewer && this.nextStaticRefreshMillis > 0L && now >= this.nextStaticRefreshMillis) {
                this.requestRefresh("scheduled verification", false);
            }

            if (this.staticBuildJob != null && this.staticBuildJob.expectedVersion != railways.version) {
                this.staticBuildJob = null;
                this.requestRefresh("topology changed during refresh", true);
            }
            if (this.refreshRequested && this.staticBuildJob == null
                && (recentViewer || this.forceRefreshRequested)) {
                this.beginStaticRefresh(railways);
            }
            if (this.staticBuildJob != null) {
                this.processStaticRefresh(railways);
            }

            this.ticks++;
            if (this.ticks % Math.max(5, Config.CREATE_MAP_UPDATE_INTERVAL_TICKS) == 0) {
                final boolean combinedViewer = this.eventStream.hasViewers("create-dynamic")
                    || this.hasRecentEndpointViewer(DYNAMIC_PATH, "/api/dynamic");
                final boolean signalViewer = combinedViewer || this.eventStream.hasViewers("create-signals") || this.hasRecentEndpointViewer(SIGNALS_PATH, "/api/signals");
                final boolean blockViewer = combinedViewer || this.eventStream.hasViewers("create-blocks") || this.hasRecentEndpointViewer(BLOCKS_PATH, "/api/blocks");
                final boolean trainViewer = combinedViewer || this.eventStream.hasViewers("create-trains") || this.hasRecentEndpointViewer(TRAINS_PATH, "/api/trains");
                if (signalViewer || blockViewer || trainViewer) {
                    final long captureStarted = System.nanoTime();
                    this.captureDynamic(server, railways, signalViewer, blockViewer, trainViewer, combinedViewer);
                    this.lastDynamicCaptureMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - captureStarted);
                    this.maxMainThreadMicros = Math.max(this.maxMainThreadMicros, this.lastDynamicCaptureMicros);
                }
            }
            if (this.ticks % 20 == 0) {
                this.publishStatus();
            }
        } catch (final Throwable throwable) {
            this.lastError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            this.staticBuildJob = null;
            this.requestRefresh("retry after update error", false);
            Logging.logger().error("Create track-map update failed; it will retry on the next cycle", throwable);
            this.publishStatus();
        }
    }

    private void applyEnabled(final boolean enabled) {
        this.enabled = enabled;
        this.lifecycleGeneration.incrementAndGet();
        this.staticBuildJob = null;
        this.pendingDynamic.set(null);
        this.refreshRequested = false;
        this.forceRefreshRequested = false;
        this.lastError = null;
        this.refreshProgress = 0.0D;
        this.lastStaticSliceMicros = 0L;
        this.lastDynamicCaptureMicros = 0L;
        this.maxMainThreadMicros = 0L;
        this.refreshReason = enabled ? "waiting for viewer" : "disabled";
        this.publishingSequence = -1L;
        this.lastPublishedSignals = List.of();
        this.lastPublishedBlocks = List.of();
        this.lastPublishedTrains = List.of();

        if (enabled) {
            this.jsonCache.putVolatile(DYNAMIC_PATH, "{\"updated_at\":0,\"signals\":[],\"blocks\":[],\"trains\":[]}");
            this.jsonCache.putVolatile(SIGNALS_PATH, "{\"updated_at\":0,\"signals\":[]}");
            this.jsonCache.putVolatile(BLOCKS_PATH, "{\"updated_at\":0,\"blocks\":[]}");
            this.jsonCache.putVolatile(TRAINS_PATH, "{\"updated_at\":0,\"trains\":[]}");
            this.observedRailwayVersion = Integer.MIN_VALUE;
            this.publishedRailwayVersion = Integer.MIN_VALUE;
            this.lastStaticRefreshMillis = 0L;
            this.nextStaticRefreshMillis = 0L;
            this.requestRefresh("startup", false);
        } else {
            this.clearPublishedData(false);
        }
        this.publishStatus();
    }

    private void clearPublishedData(final boolean includeStatus) {
        this.lastNetworkState = null;
        for (final String topic : List.of("create-signals", "create-blocks", "create-trains")) this.publishWorldCollection(topic, List.of());
        this.signalRefs = Map.of();
        this.knownSignalBlocks = Set.of();
        this.publishedTrackCount = 0;
        this.publishedStationCount = 0;
        this.publishedSignalBlockCount = 0;
        this.publishedTrainCount = 0;
        this.jsonCache.put(NETWORK_PATH, null);
        this.jsonCache.put(DYNAMIC_PATH, null);
        this.jsonCache.put(SIGNALS_PATH, null);
        this.jsonCache.put(BLOCKS_PATH, null);
        this.jsonCache.put(TRAINS_PATH, null);
        this.jsonCache.put("/api/network", null);
        this.publishAliases(null, null, null, null);
        if (includeStatus) {
            this.jsonCache.put(STATUS_PATH, null);
            this.jsonCache.put(REFRESH_PATH, null);
        }
    }

    private void handleWebRefreshRequest() {
        final long access = this.jsonCache.lastAccessNanos(REFRESH_PATH);
        if (access == Long.MIN_VALUE || access <= this.lastHandledWebRefreshAccess) {
            return;
        }
        this.lastHandledWebRefreshAccess = access;
        final long now = System.currentTimeMillis();
        if (now < this.webRefreshCooldownUntilMillis) {
            this.publishStatus();
            return;
        }
        this.webRefreshCooldownUntilMillis = now
            + TimeUnit.SECONDS.toMillis(Config.CREATE_MAP_WEB_REFRESH_COOLDOWN_SECONDS);
        this.requestRefresh("web request", true);
    }

    private boolean hasRecentViewer() {
        final long timeout = TimeUnit.SECONDS.toNanos(Config.CREATE_MAP_IDLE_TIMEOUT_SECONDS);
        return this.eventStream.hasViewers("base") || this.hasDynamicSubscribers()
            || this.jsonCache.accessedWithin(STATUS_PATH, timeout)
            || this.jsonCache.accessedWithin(NETWORK_PATH, timeout)
            || this.jsonCache.accessedWithin("/api/network", timeout)
            || this.jsonCache.accessedWithin(DYNAMIC_PATH, timeout)
            || this.jsonCache.accessedWithin(SIGNALS_PATH, timeout)
            || this.jsonCache.accessedWithin(BLOCKS_PATH, timeout)
            || this.jsonCache.accessedWithin(TRAINS_PATH, timeout)
            || this.jsonCache.accessedWithin(REFRESH_PATH, timeout);
    }

    private boolean hasRecentDynamicViewer() {
        return this.hasDynamicSubscribers()
            || this.hasRecentEndpointViewer(DYNAMIC_PATH, "/api/dynamic")
            || this.hasRecentEndpointViewer(SIGNALS_PATH, "/api/signals")
            || this.hasRecentEndpointViewer(BLOCKS_PATH, "/api/blocks")
            || this.hasRecentEndpointViewer(TRAINS_PATH, "/api/trains");
    }

    private boolean hasRecentEndpointViewer(final String path, final String alias) {
        final long timeout = TimeUnit.SECONDS.toNanos(Config.CREATE_MAP_IDLE_TIMEOUT_SECONDS);
        return this.jsonCache.accessedWithin(path, timeout) || this.jsonCache.accessedWithin(alias, timeout);
    }

    private boolean hasDynamicSubscribers() {
        return this.eventStream.hasViewers("create-dynamic") || this.eventStream.hasViewers("create-signals")
            || this.eventStream.hasViewers("create-blocks") || this.eventStream.hasViewers("create-trains");
    }

    private void beginStaticRefresh(final GlobalRailwayManager railways) {
        this.refreshRequested = false;
        this.forceRefreshRequested = false;
        final long sequence = ++this.refreshSequence;
        this.staticBuildJob = new StaticBuildJob(
            sequence,
            railways.version,
            this.requestedRefreshReason,
            List.copyOf(railways.trackNetworks.values())
        );
        this.refreshReason = this.requestedRefreshReason;
        this.refreshProgress = 0.01D;
        this.lastError = null;
        this.publishStatus();
    }

    private void processStaticRefresh(final GlobalRailwayManager railways) {
        final StaticBuildJob job = this.staticBuildJob;
        if (job == null) {
            return;
        }
        if (job.expectedVersion != railways.version) {
            this.staticBuildJob = null;
            this.requestRefresh("topology changed during refresh", true);
            return;
        }

        final long budgetNanos = TimeUnit.MICROSECONDS.toNanos(Config.CREATE_MAP_STATIC_TICK_BUDGET_MICROS);
        final long started = System.nanoTime();
        boolean hasMore;
        do {
            hasMore = job.processOne();
            this.refreshProgress = job.progress();
        } while (hasMore && System.nanoTime() - started < budgetNanos);

        this.lastStaticSliceMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        this.maxMainThreadMicros = Math.max(this.maxMainThreadMicros, this.lastStaticSliceMicros);

        if (!hasMore) {
            this.staticBuildJob = null;
            this.refreshProgress = 0.96D;
            this.publishStaticSnapshot(job.snapshot());
        }
    }

    private void publishStaticSnapshot(final StaticSnapshot snapshot) {
        final long generation = this.lifecycleGeneration.get();
        this.publishingSequence = snapshot.sequence;
        this.worker.execute(() -> {
            try {
                final List<Map<String, Object>> trackGeometry = snapshot.tracks.stream()
                    .map(TrackSnapshot::toJson)
                    .toList();
                final List<Map<String, Object>> blockGeometry = new ArrayList<>(snapshot.signalBlockPaths.size());
                for (final Map.Entry<UUID, List<SampledPath>> entry : snapshot.signalBlockPaths.entrySet()) {
                    final Map<String, Object> block = new LinkedHashMap<>();
                    block.put("id", entry.getKey().toString());
                    block.put("segments", entry.getValue().stream().map(SampledPath::toJson).toList());
                    blockGeometry.add(block);
                }

                final long updatedAt = System.currentTimeMillis();
                final Map<String, Object> data = new LinkedHashMap<>();
                data.put("version", snapshot.railwayVersion);
                data.put("tracks", trackGeometry);
                data.put("portals", snapshot.portals);
                data.put("stations", snapshot.stations);
                data.put("signals", snapshot.signalGeometry);
                data.put("signal_blocks", blockGeometry);
                final String networkState = Util.gson().toJson(data);
                final boolean changed = !networkState.equals(this.lastNetworkState);
                data.put("updated_at", updatedAt);
                final String json = Util.gson().toJson(data);

                if (!this.validPublication(generation, snapshot.sequence)) {
                    return;
                }
                if (changed) {
                    this.jsonCache.put(NETWORK_PATH, json);
                    this.jsonCache.putVolatile("/api/network", json);
                    this.lastNetworkState = networkState;
                }
                this.signalRefs = snapshot.signalRefs;
                this.knownSignalBlocks = Set.copyOf(snapshot.signalBlockPaths.keySet());
                final Map<String, Set<String>> worlds = new LinkedHashMap<>();
                for (final Map<String, Object> signal : snapshot.signalGeometry) {
                    worlds.put(String.valueOf(signal.get("id")), Set.of(String.valueOf(signal.get("world"))));
                }
                snapshot.signalBlockPaths.forEach((id, paths) -> worlds.put(id.toString(), paths.stream().map(SampledPath::world).collect(java.util.stream.Collectors.toSet())));
                this.dynamicWorlds = worlds;
                this.publishedRailwayVersion = snapshot.railwayVersion;
                if (changed) {
                    this.networkRevision = updatedAt;
                    this.eventStream.publish("create-network", Util.gson().toJson(Map.of(
                        "version", snapshot.railwayVersion,
                        "revision", updatedAt
                    )), "base");
                }
                this.lastStaticRefreshMillis = updatedAt;
                this.nextStaticRefreshMillis = updatedAt
                    + TimeUnit.SECONDS.toMillis(Config.CREATE_MAP_STATIC_REFRESH_INTERVAL_SECONDS);
                this.publishedTrackCount = snapshot.tracks.size();
                this.publishedStationCount = snapshot.stations.size();
                this.publishedSignalBlockCount = snapshot.signalBlockPaths.size();
                this.refreshProgress = 1.0D;
                this.lastError = null;
            } catch (final Throwable throwable) {
                if (snapshot.sequence == this.refreshSequence) {
                    this.lastError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                    this.requestRefresh("retry after serialization error", false);
                }
                Logging.logger().error("Failed to serialize Create railway topology", throwable);
            } finally {
                if (snapshot.sequence == this.refreshSequence) {
                    this.publishingSequence = -1L;
                    this.publishStatus();
                }
            }
        });
    }

    private boolean validPublication(final long generation, final long sequence) {
        return this.running && this.enabled && generation == this.lifecycleGeneration.get()
            && sequence == this.refreshSequence;
    }

    private void captureDynamic(
        final MinecraftServer server,
        final GlobalRailwayManager railways,
        final boolean captureSignals,
        final boolean captureBlocks,
        final boolean captureTrains,
        final boolean publishCombined
    ) {
        final @Nullable List<Map<String, Object>> signals = captureSignals ? this.signalStates() : null;
        final @Nullable List<Map<String, Object>> blocks = captureBlocks ? this.activeBlocks(railways) : null;
        final @Nullable List<Map<String, Object>> trains = captureTrains ? this.trains(server, railways) : null;
        final long updatedAt = System.currentTimeMillis();
        if (trains != null) {
            this.publishedTrainCount = trains.size();
        }

        this.pendingDynamic.set(new DynamicPublishTask(
            this.lifecycleGeneration.get(),
            updatedAt,
            signals,
            blocks,
            trains,
            publishCombined
        ));
        this.scheduleDynamicWorker();
    }

    private void scheduleDynamicWorker() {
        if (!this.dynamicWorkerScheduled.compareAndSet(false, true)) {
            return;
        }
        this.worker.execute(() -> {
            try {
                DynamicPublishTask task;
                while ((task = this.pendingDynamic.getAndSet(null)) != null) {
                    this.publishDynamic(task);
                }
            } finally {
                this.dynamicWorkerScheduled.set(false);
                if (this.pendingDynamic.get() != null) {
                    this.scheduleDynamicWorker();
                }
            }
        });
    }

    private void publishDynamic(final DynamicPublishTask task) {
        if (!this.running || !this.enabled || task.generation != this.lifecycleGeneration.get()) {
            return;
        }

        @Nullable String signalsJson = null;
        @Nullable String blocksJson = null;
        @Nullable String trainsJson = null;
        if (task.signals != null && !task.signals.equals(this.lastPublishedSignals)) {
            signalsJson = Util.gson().toJson(Map.of("updated_at", task.updatedAt, "signals", task.signals));
            this.jsonCache.putVolatile(SIGNALS_PATH, signalsJson);
            this.jsonCache.putVolatile("/api/signals", signalsJson);
            this.eventStream.publish("create-signals", signalsJson, "create-dynamic");
            this.lastPublishedSignals = task.signals;
        }
        if (task.signals != null) this.publishWorldCollection("create-signals", task.signals);
        if (task.blocks != null && !task.blocks.equals(this.lastPublishedBlocks)) {
            blocksJson = Util.gson().toJson(Map.of("updated_at", task.updatedAt, "blocks", task.blocks));
            this.jsonCache.putVolatile(BLOCKS_PATH, blocksJson);
            this.jsonCache.putVolatile("/api/blocks", blocksJson);
            this.eventStream.publish("create-blocks", blocksJson, "create-dynamic");
            this.lastPublishedBlocks = task.blocks;
        }
        if (task.blocks != null) this.publishWorldCollection("create-blocks", task.blocks);
        if (task.trains != null && !task.trains.equals(this.lastPublishedTrains)) {
            trainsJson = Util.gson().toJson(Map.of("updated_at", task.updatedAt, "trains", task.trains));
            this.jsonCache.putVolatile(TRAINS_PATH, trainsJson);
            this.jsonCache.putVolatile("/api/trains", trainsJson);
            this.eventStream.publish("create-trains", trainsJson, "create-dynamic");
            this.lastPublishedTrains = task.trains;
        }
        if (task.trains != null) this.publishWorldCollection("create-trains", task.trains);
        if (task.publishCombined) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("updated_at", task.updatedAt);
            data.put("signals", task.signals == null ? List.of() : task.signals);
            data.put("blocks", task.blocks == null ? List.of() : task.blocks);
            data.put("trains", task.trains == null ? List.of() : task.trains);
            final String dynamicJson = Util.gson().toJson(data);
            this.jsonCache.putVolatile(DYNAMIC_PATH, dynamicJson);
            this.jsonCache.putVolatile("/api/dynamic", dynamicJson);
        }
        this.lastDynamicRefreshMillis = task.updatedAt;
    }

    @SuppressWarnings("unchecked")
    private void publishWorldCollection(final String topic, final List<Map<String, Object>> rows) {
        final Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (final Map<String, Object> row : rows) {
            if (topic.equals("create-trains")) {
                final List<Map<String, Object>> positions = (List<Map<String, Object>>) row.get("positions");
                for (final String world : positions.stream().map(position -> (String) position.get("world")).collect(java.util.stream.Collectors.toSet())) {
                    final Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("positions", positions.stream().filter(position -> world.equals(position.get("world"))).toList());
                    grouped.computeIfAbsent(world, ignored -> new ArrayList<>()).add(copy);
                }
            } else {
                for (final String world : this.dynamicWorlds.getOrDefault(String.valueOf(row.get("id")), Set.of())) {
                    grouped.computeIfAbsent(world, ignored -> new ArrayList<>()).add(row);
                }
            }
        }
        final Set<String> known = this.streamWorlds.computeIfAbsent(topic, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        known.addAll(grouped.keySet());
        for (final String world : known) this.eventStream.publishCollection(topic, world, "id", grouped.getOrDefault(world, List.of()), Map.of());
    }

    private List<Map<String, Object>> signalStates() {
        final Map<UUID, SignalRef> refs = this.signalRefs;
        final List<Map<String, Object>> signals = new ArrayList<>(refs.size());
        for (final SignalRef ref : refs.values()) {
            signals.add(ref.stateJson());
        }
        return signals;
    }

    private List<Map<String, Object>> activeBlocks(final GlobalRailwayManager railways) {
        final Set<UUID> occupied = new HashSet<>();
        final Set<UUID> reserved = new HashSet<>();
        for (final Train train : railways.trains.values()) {
            occupied.addAll(train.occupiedSignalBlocks.keySet());
            reserved.addAll(train.reservedSignalBlocks);
        }

        final Set<UUID> active = new HashSet<>(occupied);
        active.addAll(reserved);
        active.retainAll(this.knownSignalBlocks);
        final List<Map<String, Object>> blocks = new ArrayList<>(active.size());
        for (final UUID id : active) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id.toString());
            entry.put("occupied", occupied.contains(id));
            entry.put("reserved", reserved.contains(id));
            blocks.add(entry);
        }
        return blocks;
    }

    private List<Map<String, Object>> trains(
        final MinecraftServer server,
        final GlobalRailwayManager railways
    ) {
        final List<Map<String, Object>> trains = new ArrayList<>(railways.trains.size());
        for (final Train train : railways.trains.values()) {
            final boolean stopped = Math.abs(train.speed) < 0.01D;
            // Match Create Track Map: the actual travel direction is defined by
            // the sign of speed. currentlyBackwards describes train/schedule
            // orientation and must not reverse a moving train a second time.
            final boolean movingBackwards = train.speed < -0.0001D;
            final boolean orientationBackwards = stopped ? train.currentlyBackwards : movingBackwards;
            final List<Map<String, Object>> positions = trainPositions(train, orientationBackwards);
            if (positions.isEmpty()) {
                continue;
            }

            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", train.id.toString());
            entry.put("name", train.name.getString());
            entry.put("owner", ownerName(server, train.owner));
            entry.put("positions", positions);
            entry.put("carriages", train.carriages.size());
            entry.put("backwards", movingBackwards);
            entry.put("stopped", stopped);
            entry.put("speed", train.speed);
            entry.put("derailed", train.derailed || train.graph == null);
            entry.put("color", train.mapColorIndex);
            trains.add(entry);
        }
        return trains;
    }

    /**
     * Only the front and rear carriages are sampled. The marker for the current
     * dimension is anchored at the actual head bogey, matching Create Track
     * Map's direction calculation. A rear-car candidate is kept only for a
     * second dimension while a long train crosses a portal.
     */
    private static List<Map<String, Object>> trainPositions(
        final Train train,
        final boolean backwards
    ) {
        if (train.graph == null || train.carriages.isEmpty()) {
            return List.of();
        }

        final int frontIndex = backwards ? train.carriages.size() - 1 : 0;
        final int rearIndex = backwards ? 0 : train.carriages.size() - 1;
        final Set<Integer> indices = new LinkedHashSet<>();
        indices.add(frontIndex);
        indices.add(rearIndex);
        final Map<String, PositionCandidate> byWorld = new LinkedHashMap<>();

        for (final int index : indices) {
            final Carriage carriage = train.carriages.get(index);
            final @Nullable PositionedPoint leading = travellingPoint(train, carriage.getLeadingPoint());
            final @Nullable PositionedPoint trailing = travellingPoint(train, carriage.getTrailingPoint());
            final boolean frontCar = index == frontIndex;
            final int priority = frontCar ? 3 : 1;

            if (leading != null && trailing != null && leading.world.equals(trailing.world)) {
                final PositionedPoint head = backwards ? trailing : leading;
                final PositionedPoint tail = backwards ? leading : trailing;
                putCandidate(byWorld, new PositionCandidate(
                    head.world,
                    head.dimension,
                    frontCar ? head.point : head.point.add(tail.point).scale(0.5D),
                    headingAngle(head.point, tail.point),
                    priority,
                    frontCar
                ));
                continue;
            }

            // Portal transition: keep one candidate per visible dimension. The
            // local edge tangent is used because the other bogey may already be
            // in another dimension.
            if (leading != null) {
                final boolean isHead = frontCar && !backwards;
                putCandidate(byWorld, new PositionCandidate(
                    leading.world,
                    leading.dimension,
                    leading.point,
                    localHeading(carriage.getLeadingPoint(), isHead, backwards),
                    isHead ? 3 : priority,
                    isHead
                ));
            }
            if (trailing != null) {
                final boolean isHead = frontCar && backwards;
                putCandidate(byWorld, new PositionCandidate(
                    trailing.world,
                    trailing.dimension,
                    trailing.point,
                    localHeading(carriage.getTrailingPoint(), isHead, backwards),
                    isHead ? 3 : priority,
                    isHead
                ));
            }
        }

        final List<Map<String, Object>> result = new ArrayList<>(byWorld.size());
        for (final PositionCandidate candidate : byWorld.values()) {
            result.add(candidate.toJson());
        }
        return result;
    }

    /** CSS/Leaflet rotation: 0deg points east, positive angles rotate south. */
    private static double headingAngle(final Vec3 head, final Vec3 tail) {
        return normalizeAngle(Math.toDegrees(Math.atan2(
            head.z - tail.z,
            head.x - tail.x
        )));
    }

    private static double localHeading(
        final TravellingPoint point,
        final boolean isHead,
        final boolean backwards
    ) {
        if (point.edge == null || point.edge.getLength() <= 0.0001D) {
            return 0.0D;
        }
        final Vec3 tangent = point.edge.getDirectionAt(point.position);
        double angle = normalizeAngle(Math.toDegrees(Math.atan2(tangent.z, tangent.x)));

        // TravellingPoint's edge tangent follows node1 -> node2. For the train
        // head we need tail -> head. The endpoint role gives the required sign.
        final boolean reverseTangent = (isHead && backwards) || (!isHead && !backwards);
        if (reverseTangent) {
            angle = normalizeAngle(angle + 180.0D);
        }
        return angle;
    }

    private static double normalizeAngle(final double angle) {
        final double normalized = angle % 360.0D;
        return normalized < 0.0D ? normalized + 360.0D : normalized;
    }

    private static void putCandidate(
        final Map<String, PositionCandidate> candidates,
        final PositionCandidate candidate
    ) {
        final @Nullable PositionCandidate current = candidates.get(candidate.world);
        if (current == null || candidate.priority > current.priority) {
            candidates.put(candidate.world, candidate);
        }
    }

    private synchronized void publishStatus() {
        if (!this.running) {
            return;
        }
        final long now = System.currentTimeMillis();
        final boolean recentViewer = this.hasRecentViewer();
        final boolean refreshing = this.refreshing();
        final Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", true);
        status.put("enabled", this.enabled);
        status.put("sleeping", this.enabled && !recentViewer && !refreshing);
        status.put("dynamic_sleeping", this.enabled && !this.hasRecentDynamicViewer());
        status.put("active_recently", recentViewer);
        status.put("update_interval_ticks", Config.CREATE_MAP_UPDATE_INTERVAL_TICKS);
        status.put("idle_timeout_seconds", Config.CREATE_MAP_IDLE_TIMEOUT_SECONDS);
        status.put("static_tick_budget_micros", Config.CREATE_MAP_STATIC_TICK_BUDGET_MICROS);
        status.put("network_version", this.publishedRailwayVersion);
        status.put("network_revision", this.networkRevision);
        status.put("refreshing", refreshing);
        status.put("refresh_progress", refreshing ? this.refreshProgress : 1.0D);
        status.put("refresh_reason", this.refreshReason);
        status.put("last_static_refresh", this.lastStaticRefreshMillis);
        status.put("next_static_refresh", this.nextStaticRefreshMillis);
        status.put("last_dynamic_refresh", this.lastDynamicRefreshMillis);
        status.put("refresh_cooldown_until", this.webRefreshCooldownUntilMillis);
        status.put("track_count", this.publishedTrackCount);
        status.put("station_count", this.publishedStationCount);
        status.put("signal_block_count", this.publishedSignalBlockCount);
        status.put("train_count", this.publishedTrainCount);
        status.put("last_static_slice_micros", this.lastStaticSliceMicros);
        status.put("last_dynamic_capture_micros", this.lastDynamicCaptureMicros);
        status.put("max_main_thread_micros", this.maxMainThreadMicros);
        status.put("server_time", now);
        status.put("error", this.lastError);
        final String json = Util.gson().toJson(status);
        this.jsonCache.putVolatile(STATUS_PATH, json);
        final Map<String, Object> state = new LinkedHashMap<>(status);
        // Clock and diagnostic samples are supplied by HTTP; they must not force one SSE packet per second.
        for (final String key : List.of("server_time", "last_dynamic_refresh", "last_static_slice_micros", "last_dynamic_capture_micros", "max_main_thread_micros")) state.remove(key);
        if (refreshing) state.put("refresh_progress", Math.floor(this.refreshProgress * 20) / 20);
        final String stateJson = Util.gson().toJson(state);
        final long monotonicNow = System.nanoTime();
        if (!stateJson.equals(this.lastStatusState) && monotonicNow - this.lastStatusEventNanos >= TimeUnit.MILLISECONDS.toNanos(500)) {
            this.lastStatusState = stateJson;
            this.lastStatusEventNanos = monotonicNow;
            this.eventStream.publishState("create-status", stateJson);
        }
    }

    private void publishAliases(
        final @Nullable String network,
        final @Nullable String signals,
        final @Nullable String blocks,
        final @Nullable String trains
    ) {
        if (network != null) {
            this.jsonCache.putVolatile("/api/network", network);
        }
        this.jsonCache.putVolatile("/api/signals", signals);
        this.jsonCache.putVolatile("/api/blocks", blocks);
        this.jsonCache.putVolatile("/api/trains", trains);
    }

    private static Map<String, Object> station(
        final TrackGraph graph,
        final TrackEdge edge,
        final GlobalStation station
    ) {
        final double distance = station.getLocationOn(edge);
        final Vec3 location = edge.getPosition(graph, distance / Math.max(edge.getLength(), 0.0001D));
        final Vec3 direction = edge.getDirectionAt(distance);
        double angle = angle(direction);
        if (station.isPrimary(edge.node1)) {
            angle += 180.0D;
        }

        final Map<String, Object> result = pointMap(edge.node1.getLocation().getDimension(), location);
        result.put("id", station.id.toString());
        result.put("name", station.name);
        result.put("angle", round2(angle));
        result.put("assembling", station.assembling);
        return result;
    }

    private static Map<String, Object> portal(final TrackEdge edge) {
        return Map.of(
            "from", pointMap(edge.node1.getLocation().getDimension(), edge.node1.getLocation().getLocation()),
            "to", pointMap(edge.node2.getLocation().getDimension(), edge.node2.getLocation().getLocation())
        );
    }

    private static @Nullable PositionedPoint travellingPoint(
        final Train train,
        final @Nullable TravellingPoint point
    ) {
        if (point == null || point.node1 == null || point.edge == null || train.graph == null) {
            return null;
        }
        final ResourceKey<Level> dimension = point.node1.getLocation().getDimension();
        return new PositionedPoint(
            webWorldName(dimension),
            dimension.location().toString(),
            point.getPosition(train.graph)
        );
    }

    private static @Nullable String ownerName(final MinecraftServer server, final @Nullable UUID owner) {
        if (owner == null) {
            return null;
        }
        final @Nullable ServerPlayer player = server.getPlayerList().getPlayer(owner);
        return player == null ? owner.toString() : player.getGameProfile().getName();
    }

    private static Map<String, Object> pointMap(final ResourceKey<Level> dimension, final Vec3 point) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("world", webWorldName(dimension));
        result.put("dimension", dimension.location().toString());
        result.put("x", round3(point.x));
        result.put("y", round3(point.y));
        result.put("z", round3(point.z));
        return result;
    }

    private static Map<String, Object> rawPoint(final Vec3 point) {
        return Map.of("x", round3(point.x), "y", round3(point.y), "z", round3(point.z));
    }

    private static String webWorldName(final ResourceKey<Level> dimension) {
        return dimension.location().toString().replace(':', '_');
    }

    private static String edgeKey(final TrackEdge edge) {
        final String first = nodeKey(edge.node1.getLocation());
        final String second = nodeKey(edge.node2.getLocation());
        return first.compareTo(second) <= 0 ? first + '|' + second : second + '|' + first;
    }

    private static String nodeKey(final TrackNodeLocation location) {
        return location.getDimension().location() + ":" + location.getX() + ':' + location.getY() + ':'
            + location.getZ() + ':' + location.yOffsetPixels;
    }

    private static double angle(final Vec3 vector) {
        return Math.toDegrees(Math.atan2(vector.x, -vector.z));
    }

    private static double round2(final double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static double round3(final double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private final class StaticBuildJob {
        private final long sequence;
        private final int expectedVersion;
        private final String reason;
        private final List<TrackGraph> graphs;
        private final int totalNodes;
        private final List<TrackSnapshot> tracks = new ArrayList<>();
        private final List<Map<String, Object>> portals = new ArrayList<>();
        private final List<Map<String, Object>> stations = new ArrayList<>();
        private final List<Map<String, Object>> signalGeometry = new ArrayList<>();
        private final Map<UUID, SignalRef> signalRefs = new LinkedHashMap<>();
        private final Map<UUID, List<SampledPath>> signalBlockPaths = new LinkedHashMap<>();
        private final Set<String> seenEdges = new HashSet<>();
        private final Set<UUID> seenStations = new HashSet<>();
        private int graphIndex;
        private int edgeIndex;
        private int processedNodes;
        private @Nullable TrackGraph graph;
        private Iterator<TrackNodeLocation> nodes = Collections.emptyIterator();
        private List<TrackEdge> edges = List.of();

        StaticBuildJob(
            final long sequence,
            final int expectedVersion,
            final String reason,
            final List<TrackGraph> graphs
        ) {
            this.sequence = sequence;
            this.expectedVersion = expectedVersion;
            this.reason = reason;
            this.graphs = graphs;
            this.totalNodes = Math.max(1, graphs.stream().mapToInt(graph -> graph.getNodes().size()).sum());
        }

        boolean processOne() {
            while (true) {
                if (this.edgeIndex < this.edges.size()) {
                    this.processEdge(this.edges.get(this.edgeIndex++));
                    return true;
                }
                if (this.nodes.hasNext()) {
                    final TrackNodeLocation location = this.nodes.next();
                    this.processedNodes++;
                    final @Nullable TrackNode node = this.graph == null ? null : this.graph.locateNode(location);
                    this.edges = node == null || this.graph == null
                        ? List.of()
                        : List.copyOf(this.graph.getConnectionsFrom(node).values());
                    this.edgeIndex = 0;
                    continue;
                }
                if (this.graphIndex < this.graphs.size()) {
                    this.graph = this.graphs.get(this.graphIndex++);
                    this.nodes = this.graph.getNodes().iterator();
                    this.edges = List.of();
                    this.edgeIndex = 0;
                    continue;
                }
                return false;
            }
        }

        double progress() {
            return Math.min(0.94D, 0.02D + (this.processedNodes / (double) this.totalNodes) * 0.92D);
        }

        StaticSnapshot snapshot() {
            return new StaticSnapshot(
                this.sequence,
                this.expectedVersion,
                this.reason,
                List.copyOf(this.tracks),
                List.copyOf(this.portals),
                List.copyOf(this.stations),
                List.copyOf(this.signalGeometry),
                Map.copyOf(this.signalRefs),
                immutablePathMap(this.signalBlockPaths)
            );
        }

        private void processEdge(final TrackEdge edge) {
            if (!this.seenEdges.add(edgeKey(edge))) {
                return;
            }
            if (edge.isInterDimensional()) {
                this.portals.add(portal(edge));
                return;
            }
            if (this.graph == null) {
                return;
            }

            final SampledPath path = SampledPath.from(this.graph, edge);
            this.tracks.add(new TrackSnapshot(path, edge.getTrackMaterial().id.toString()));
            this.collectEdgePoints(edge);
            this.collectSignalBlockPaths(edge, path);
        }

        private void collectEdgePoints(final TrackEdge edge) {
            if (this.graph == null) {
                return;
            }
            for (final TrackEdgePoint point : edge.getEdgeData().getPoints()) {
                if (point instanceof GlobalStation station) {
                    if (this.seenStations.add(station.id)) {
                        this.stations.add(station(this.graph, edge, station));
                    }
                } else if (point instanceof SignalBoundary signal && !this.signalRefs.containsKey(signal.id)) {
                    final SignalRef ref = new SignalRef(this.graph, edge, signal);
                    this.signalRefs.put(signal.id, ref);
                    this.signalGeometry.add(ref.geometryJson());
                }
            }
        }

        private void collectSignalBlockPaths(final TrackEdge edge, final SampledPath path) {
            if (this.graph == null) {
                return;
            }
            final double length = edge.getLength();
            if (length <= 0.0001D) {
                return;
            }

            /*
             * Ported from Create Track Map's block construction. A signal owns
             * the segment on each side through getGroup(node1/node2). This is
             * more reliable than inferring a group only from a midpoint,
             * especially on bidirectional signals and adjacent boundaries.
             */
            if (!edge.getEdgeData().hasSignalBoundaries()) {
                this.addSignalBlockPath(edge.getEdgeData().getEffectiveEdgeGroupId(this.graph), path);
                return;
            }

            final List<SignalBoundary> signals = edge.getEdgeData().getPoints().stream()
                .filter(SignalBoundary.class::isInstance)
                .map(SignalBoundary.class::cast)
                .sorted((left, right) -> Double.compare(
                    left.getLocationOn(edge),
                    right.getLocationOn(edge)
                ))
                .toList();
            if (signals.isEmpty()) {
                return;
            }

            final List<Double> cuts = new ArrayList<>(signals.size() + 2);
            cuts.add(0.0D);
            for (final SignalBoundary signal : signals) {
                cuts.add(Mth.clamp(signal.getLocationOn(edge) / length, 0.0D, 1.0D));
            }
            cuts.add(1.0D);

            final List<SampledPath> segments = new ArrayList<>(cuts.size() - 1);
            for (int index = 0; index < cuts.size() - 1; index++) {
                final double from = cuts.get(index);
                final double to = cuts.get(index + 1);
                if (to - from < 0.00001D) {
                    // Preserve indexing around colocated signals with a tiny
                    // degenerate path rather than shifting all later segments.
                    segments.add(path.subPath(from, from));
                } else {
                    segments.add(path.subPath(from, to));
                }
            }

            for (int index = 0; index < signals.size(); index++) {
                final SignalBoundary signal = signals.get(index);
                this.addSignalBlockPath(signal.getGroup(edge.node1), segments.get(index));
                this.addSignalBlockPath(signal.getGroup(edge.node2), segments.get(index + 1));
            }
        }

        private void addSignalBlockPath(
            final @Nullable UUID group,
            final SampledPath path
        ) {
            if (group == null || path.points.size() < 2) {
                return;
            }
            final List<SampledPath> paths = this.signalBlockPaths.computeIfAbsent(
                group,
                ignored -> new ArrayList<>()
            );
            final String key = path.geometryKey();
            for (final SampledPath existing : paths) {
                if (existing.geometryKey().equals(key)) {
                    return;
                }
            }
            paths.add(path);
        }
    }

    private static Map<UUID, List<SampledPath>> immutablePathMap(
        final Map<UUID, List<SampledPath>> source
    ) {
        final Map<UUID, List<SampledPath>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private record StaticSnapshot(
        long sequence,
        int railwayVersion,
        String reason,
        List<TrackSnapshot> tracks,
        List<Map<String, Object>> portals,
        List<Map<String, Object>> stations,
        List<Map<String, Object>> signalGeometry,
        Map<UUID, SignalRef> signalRefs,
        Map<UUID, List<SampledPath>> signalBlockPaths
    ) {
    }

    private record TrackSnapshot(SampledPath path, String material) {
        Map<String, Object> toJson() {
            return this.path.toJson(this.material);
        }
    }

    private record DynamicPublishTask(
        long generation,
        long updatedAt,
        @Nullable List<Map<String, Object>> signals,
        @Nullable List<Map<String, Object>> blocks,
        @Nullable List<Map<String, Object>> trains,
        boolean publishCombined
    ) {
    }

    private record PositionedPoint(String world, String dimension, Vec3 point) {
    }

    private record PositionCandidate(
        String world,
        String dimension,
        Vec3 point,
        double angle,
        int priority,
        boolean front
    ) {
        Map<String, Object> toJson() {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("world", this.world);
            data.put("dimension", this.dimension);
            data.put("x", round3(this.point.x));
            data.put("y", round3(this.point.y));
            data.put("z", round3(this.point.z));
            data.put("angle", round2(this.angle));
            data.put("front", this.front);
            return data;
        }
    }

    private record SignalRef(TrackGraph graph, TrackEdge edge, SignalBoundary signal) {
        Map<String, Object> geometryJson() {
            final double distance = this.signal.getLocationOn(this.edge);
            final Vec3 location = this.edge.getPosition(this.graph, distance / Math.max(this.edge.getLength(), 0.0001D));
            final double forwardAngle = angle(this.edge.getDirectionAt(distance))
                + (this.signal.isPrimary(this.edge.node1) ? 180.0D : 0.0D);

            final Map<String, Object> data = pointMap(this.edge.node1.getLocation().getDimension(), location);
            data.put("id", this.signal.id.toString());
            data.put("forward", this.sideGeometry(true, forwardAngle));
            data.put("reverse", this.sideGeometry(false, forwardAngle + 180.0D));
            return data;
        }

        Map<String, Object> stateJson() {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", this.signal.id.toString());
            data.put("forward", this.sideState(true));
            data.put("reverse", this.sideState(false));
            return data;
        }

        private @Nullable Map<String, Object> sideGeometry(final boolean first, final double angle) {
            if (this.signal.blockEntities.get(first).isEmpty()) {
                return null;
            }
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("angle", round2(angle));
            final @Nullable UUID block = this.signal.groups.get(first);
            data.put("block", block == null ? null : block.toString());
            final @Nullable SignalType type = this.signal.types.get(first);
            data.put("type", type == null ? SignalType.ENTRY_SIGNAL.name() : type.name());
            return data;
        }

        private @Nullable Map<String, Object> sideState(final boolean first) {
            if (this.signal.blockEntities.get(first).isEmpty()) {
                return null;
            }
            final @Nullable SignalState state = this.signal.cachedStates.get(first);
            final @Nullable SignalType type = this.signal.types.get(first);
            if (type == null) {
                return null;
            }
            return Map.of(
                "state", state == null ? SignalState.INVALID.name() : state.name(),
                "type", type.name()
            );
        }
    }

    private record SampledPath(String world, String dimension, List<Vec3> points) {
        static SampledPath from(final TrackGraph graph, final TrackEdge edge) {
            final int samples;
            if (edge.isTurn()) {
                samples = Mth.clamp((int) Math.ceil(edge.getLength() / 4.0D), 6, 40);
            } else if (edge.node1.getLocation().yOffsetPixels != 0 || edge.node2.getLocation().yOffsetPixels != 0) {
                samples = 4;
            } else {
                samples = 1;
            }

            final List<Vec3> points = new ArrayList<>(samples + 1);
            for (int index = 0; index <= samples; index++) {
                points.add(edge.getPosition(graph, index / (double) samples));
            }
            final ResourceKey<Level> dimension = edge.node1.getLocation().getDimension();
            return new SampledPath(webWorldName(dimension), dimension.location().toString(), List.copyOf(points));
        }

        SampledPath subPath(final double from, final double to) {
            final double start = Mth.clamp(from, 0.0D, 1.0D);
            final double end = Mth.clamp(to, start, 1.0D);
            final int segmentCount = this.points.size() - 1;
            final List<Vec3> result = new ArrayList<>();
            result.add(this.at(start));

            final int firstWhole = (int) Math.ceil(start * segmentCount);
            final int lastWhole = (int) Math.floor(end * segmentCount);
            for (int index = firstWhole; index <= lastWhole; index++) {
                final double parameter = index / (double) segmentCount;
                if (parameter > start + 0.000001D && parameter < end - 0.000001D) {
                    result.add(this.points.get(index));
                }
            }
            result.add(this.at(end));
            return new SampledPath(this.world, this.dimension, List.copyOf(result));
        }

        private Vec3 at(final double parameter) {
            final int segmentCount = this.points.size() - 1;
            final double scaled = Mth.clamp(parameter, 0.0D, 1.0D) * segmentCount;
            final int lower = Math.min((int) Math.floor(scaled), segmentCount - 1);
            final double local = scaled - lower;
            return this.points.get(lower).lerp(this.points.get(lower + 1), local);
        }

        String geometryKey() {
            final StringBuilder key = new StringBuilder(this.world).append('|');
            for (final Vec3 point : this.points) {
                key.append(round3(point.x)).append(',')
                    .append(round3(point.y)).append(',')
                    .append(round3(point.z)).append(';');
            }
            return key.toString();
        }

        Map<String, Object> toJson() {
            return this.toJson(null);
        }

        Map<String, Object> toJson(final @Nullable String material) {
            final Map<String, Object> data = new LinkedHashMap<>();
            data.put("world", this.world);
            data.put("dimension", this.dimension);
            data.put("points", this.points.stream().map(CreateTrackMapIntegration::rawPoint).toList());
            if (material != null) {
                data.put("material", material);
            }
            return data;
        }
    }
}
