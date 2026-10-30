package xyz.jpenilla.squaremap.common.task;

import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import xyz.jpenilla.squaremap.api.HtmlComponentSerializer;
import xyz.jpenilla.squaremap.common.AbstractPlayerManager;
import xyz.jpenilla.squaremap.common.ServerAccess;
import xyz.jpenilla.squaremap.common.config.ConfigManager;
import xyz.jpenilla.squaremap.common.config.WorldConfig;
import xyz.jpenilla.squaremap.common.httpd.JsonCache;
import xyz.jpenilla.squaremap.common.httpd.EventStream;
import xyz.jpenilla.squaremap.common.player.PlayerHeadCache;
import xyz.jpenilla.squaremap.common.util.Util;
import xyz.jpenilla.squaremap.common.util.concurrent.GlobalBackgroundExecutor;

@DefaultQualifier(NonNull.class)
public final class UpdatePlayers implements Runnable {
    private static final String JSON_PATH = "/tiles/players.json";

    private final Provider<ComponentFlattener> flattener;
    private final AbstractPlayerManager playerManager;
    private final ServerAccess serverAccess;
    private final ConfigManager configManager;
    private final JsonCache jsonCache;
    private final EventStream events;
    private final java.util.Set<String> positionWorlds = new java.util.HashSet<>();
    private final Map<String, Long> lastPositionPublish = new HashMap<>();
    private long positionTick;
    private final PlayerHeadCache playerHeadCache;
    private final GlobalBackgroundExecutor backgroundExecutor;
    private @Nullable Map<String, Object> lastData = null;

    @Inject
    private UpdatePlayers(
        final Provider<ComponentFlattener> flattener,
        final AbstractPlayerManager playerManager,
        final ServerAccess serverAccess,
        final ConfigManager configManager,
        final JsonCache jsonCache,
        final EventStream events,
        final PlayerHeadCache playerHeadCache,
        final GlobalBackgroundExecutor backgroundExecutor
    ) {
        this.flattener = flattener;
        this.playerManager = playerManager;
        this.serverAccess = serverAccess;
        this.configManager = configManager;
        this.jsonCache = jsonCache;
        this.events = events;
        this.playerHeadCache = playerHeadCache;
        this.backgroundExecutor = backgroundExecutor;
    }

    @Override
    public void run() {
        final @Nullable Map<String, Object> prev = this.lastData;
        final Map<String, Object> data = this.collectData();
        this.lastData = data;
        final Map<String, Integer> intervals = new HashMap<>();
        this.serverAccess.levels().forEach(world -> intervals.put(Util.levelWebName(world),
            Math.max(1, this.configManager.worldConfig(world).PLAYER_TRACKER_UPDATE_INTERVAL)));

        this.backgroundExecutor.execute(() -> {
            if (prev == null || !prev.equals(data)) {
                final String json = Util.gson().toJson(data);
                this.jsonCache.put(JSON_PATH, json);
            }
            this.publishPlayers(data, intervals);
        });
    }

    @SuppressWarnings("unchecked")
    private void publishPlayers(final Map<String, Object> data, final Map<String, Integer> intervals) {
        final List<Map<String, Object>> roster = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> positions = new HashMap<>();
        final List<String> fields = List.of("x", "y", "z", "yaw", "health", "armor");
        for (final Map<String, Object> player : (List<Map<String, Object>>) data.get("players")) {
            final Map<String, Object> identity = new HashMap<>(player);
            final Map<String, Object> position = new HashMap<>();
            position.put("uuid", player.get("uuid"));
            for (final String field : fields) {
                if (identity.containsKey(field)) position.put(field, identity.remove(field));
            }
            roster.add(identity);
            positions.computeIfAbsent((String) player.get("world"), ignored -> new ArrayList<>()).add(position);
        }
        this.events.publishCollection("players-roster", "", "uuid", roster, Map.of("max", data.get("max")));
        this.positionWorlds.addAll(positions.keySet());
        final long now = ++this.positionTick;
        for (final String world : this.positionWorlds) {
            final Long last = this.lastPositionPublish.get(world);
            if (last != null && now - last < intervals.getOrDefault(world, 1)) continue;
            this.lastPositionPublish.put(world, now);
            this.events.publishCollection("players-positions", world, "uuid", positions.getOrDefault(world, List.of()), Map.of());
        }
    }

    private Map<String, Object> collectData() {
        final List<Object> players = new ArrayList<>();

        final HtmlComponentSerializer htmlComponentSerializer = HtmlComponentSerializer.withFlattener(this.flattener.get());

        this.serverAccess.levels().forEach(world -> {
            final WorldConfig worldConfig = this.configManager.worldConfig(world);

            world.players().forEach(player -> {
                if (worldConfig.PLAYER_TRACKER_HIDE_SPECTATORS && player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                    return;
                }
                if (worldConfig.PLAYER_TRACKER_HIDE_INVISIBLE && player.isInvisible()) {
                    return;
                }
                if (this.playerManager.hidden(player) || this.playerManager.otherwiseHidden(player)) {
                    return;
                }
                if (worldConfig.PLAYER_TRACKER_NAMEPLATE_SHOW_HEAD) {
                    this.playerHeadCache.ensure(player);
                }
                final Map<String, Object> playerEntry = new HashMap<>();
                final Vec3 playerLoc = player.position();
                playerEntry.put("name", player.getGameProfile().getName());
                if (worldConfig.PLAYER_TRACKER_USE_DISPLAY_NAME) {
                    playerEntry.put("display_name", htmlComponentSerializer.serialize(this.playerManager.displayName(player)));
                }
                playerEntry.put("uuid", player.getUUID().toString().replace("-", ""));
                playerEntry.put("world", Util.levelWebName(world));
                if (worldConfig.PLAYER_TRACKER_ENABLED) {
                    playerEntry.put("x", Mth.floor(playerLoc.x()));
                    playerEntry.put("y", Mth.floor(playerLoc.y()));
                    playerEntry.put("z", Mth.floor(playerLoc.z()));
                    playerEntry.put("yaw", Math.round(player.getYHeadRot()));
                    if (worldConfig.PLAYER_TRACKER_NAMEPLATE_SHOW_ARMOR) {
                        playerEntry.put("armor", armorPoints(player));
                    }
                    if (worldConfig.PLAYER_TRACKER_NAMEPLATE_SHOW_HEALTH) {
                        playerEntry.put("health", (int) player.getHealth());
                    }
                }
                players.add(playerEntry);
            });
        });

        final Map<String, Object> map = new HashMap<>();

        map.put("players", players);
        map.put("max", this.serverAccess.maxPlayers());

        return map;
    }

    private static int armorPoints(final ServerPlayer player) {
        final @Nullable AttributeInstance attribute = player.getAttribute(Attributes.ARMOR);
        return attribute == null ? 0 : (int) attribute.getValue();
    }
}
