package xyz.jpenilla.squaremap.common.config;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;

@SuppressWarnings("unused")
public final class Config extends AbstractConfig {
    private static final int LATEST_VERSION = 2;

    Config(final DirectoryProvider directoryProvider) {
        super(directoryProvider.dataDirectory(), Config.class, "config.yml", LATEST_VERSION);
    }

    @Override
    protected void addVersions(final ConfigurationTransformation.VersionedBuilder versionedBuilder) {
        final ConfigurationTransformation oneToTwo = ConfigurationTransformation.builder()
            .addAction(NodePath.path("settings", "image-quality", "compress-images"), (path, node) -> {
                final double d = node.getDouble();
                node.raw(null);
                node.node("enabled").set(d != 0.0D);
                node.node("value").set(d == 0.0D ? d : 1.0D - d);
                return null;
            })
            .build();

        versionedBuilder.addVersion(LATEST_VERSION, oneToTwo);
    }

    static Config config;

    public static void reload(final DirectoryProvider directoryProvider) {
        config = new Config(directoryProvider);
        config.readConfig(Config.class, null);
    }

    public static String LANGUAGE_FILE = "lang-en.yml";
    public static boolean DEBUG_MODE = false;
    public static boolean UPDATE_CHECKER = true;
    public static String WEB_ADDRESS = "http://localhost:8080";

    private static void baseSettings() {
        LANGUAGE_FILE = config.getString("settings.language-file", LANGUAGE_FILE);
        DEBUG_MODE = config.getBoolean("settings.debug-mode", DEBUG_MODE);
        UPDATE_CHECKER = config.getBoolean("settings.update-checker", UPDATE_CHECKER);
        WEB_ADDRESS = config.getString("settings.web-address", WEB_ADDRESS);
    }

    public static String WEB_DIR = "web";
    public static boolean UPDATE_WEB_DIR = true;

    private static void webDirSettings() {
        WEB_DIR = config.getString("settings.web-directory.path", WEB_DIR);
        UPDATE_WEB_DIR = config.getBoolean("settings.web-directory.auto-update", UPDATE_WEB_DIR);
    }

    public static boolean COMPRESS_IMAGES = false;
    private static double COMPRESSION_RATIO_CONFIG = 0.0F;
    public static float COMPRESSION_RATIO;
    public static String TILE_FORMAT = "webp";
    public static boolean TILE_KEEP_PNG = false;

    private static void imageQualitySettings() {
        COMPRESS_IMAGES = config.getBoolean("settings.image-quality.compress-images.enabled", COMPRESS_IMAGES);
        COMPRESSION_RATIO_CONFIG = config.getDouble("settings.image-quality.compress-images.value", COMPRESSION_RATIO_CONFIG);
        COMPRESSION_RATIO = (float) (1.0D - COMPRESSION_RATIO_CONFIG);
        TILE_FORMAT = config.getString("settings.image-quality.tile-format", TILE_FORMAT).toLowerCase();
        if (!TILE_FORMAT.equals("png") && !TILE_FORMAT.equals("webp")) {
            TILE_FORMAT = "png";
        }
        TILE_KEEP_PNG = config.getBoolean("settings.image-quality.keep-png", TILE_KEEP_PNG);
    }

    public static boolean HTTPD_ENABLED = true;
    public static String HTTPD_BIND = "0.0.0.0";
    public static int HTTPD_PORT = 8080;
    public static boolean FLUSH_JSON_IMMEDIATELY = false;

    private static void internalWebServerSettings() {
        HTTPD_ENABLED = config.getBoolean("settings.internal-webserver.enabled", HTTPD_ENABLED);
        HTTPD_BIND = config.getString("settings.internal-webserver.bind", HTTPD_BIND);
        HTTPD_PORT = config.getInt("settings.internal-webserver.port", HTTPD_PORT);
        FLUSH_JSON_IMMEDIATELY = config.getBoolean("settings.internal-webserver.flush-json-immediately", FLUSH_JSON_IMMEDIATELY);
    }

    public static boolean PLAYER_HEAD_CACHE_ENABLED = true;

    private static void playerHeadCacheSettings() {
        PLAYER_HEAD_CACHE_ENABLED = config.getBoolean("settings.player-head-cache.enabled", PLAYER_HEAD_CACHE_ENABLED);
    }

    public static boolean CREATE_MAP_ENABLED = true;
    public static int CREATE_MAP_UPDATE_INTERVAL_TICKS = 20;
    public static int CREATE_MAP_IDLE_TIMEOUT_SECONDS = 20;
    public static int CREATE_MAP_STATIC_REFRESH_INTERVAL_SECONDS = 300;
    public static int CREATE_MAP_STATIC_TICK_BUDGET_MICROS = 1000;
    public static int CREATE_MAP_WEB_REFRESH_COOLDOWN_SECONDS = 30;

    public static int SNAPSHOT_REQUESTS_PER_RENDER_THREAD = 16;
    public static double SNAPSHOT_THROTTLE_MSPT = 35.0D;
    public static double SNAPSHOT_PAUSE_MSPT = 47.0D;

    private static void renderProtectionSettings() {
        SNAPSHOT_REQUESTS_PER_RENDER_THREAD = Math.max(1, config.getInt(
            "settings.render-protection.snapshot-requests-per-thread", SNAPSHOT_REQUESTS_PER_RENDER_THREAD));
        SNAPSHOT_THROTTLE_MSPT = Math.max(1.0D, config.getDouble(
            "settings.render-protection.throttle-mspt", SNAPSHOT_THROTTLE_MSPT));
        SNAPSHOT_PAUSE_MSPT = Math.max(SNAPSHOT_THROTTLE_MSPT + 1.0D, config.getDouble(
            "settings.render-protection.pause-mspt", SNAPSHOT_PAUSE_MSPT));
    }

    private static void createMapSettings() {
        CREATE_MAP_ENABLED = config.getBoolean("settings.create-map.enabled", CREATE_MAP_ENABLED);
        CREATE_MAP_UPDATE_INTERVAL_TICKS = Math.max(5, config.getInt(
            "settings.create-map.update-interval-ticks",
            CREATE_MAP_UPDATE_INTERVAL_TICKS
        ));
        CREATE_MAP_IDLE_TIMEOUT_SECONDS = Math.max(5, config.getInt(
            "settings.create-map.idle-timeout-seconds",
            CREATE_MAP_IDLE_TIMEOUT_SECONDS
        ));
        CREATE_MAP_STATIC_REFRESH_INTERVAL_SECONDS = Math.max(30, config.getInt(
            "settings.create-map.static-refresh-interval-seconds",
            CREATE_MAP_STATIC_REFRESH_INTERVAL_SECONDS
        ));
        CREATE_MAP_STATIC_TICK_BUDGET_MICROS = Math.max(100, config.getInt(
            "settings.create-map.static-tick-budget-micros",
            CREATE_MAP_STATIC_TICK_BUDGET_MICROS
        ));
        CREATE_MAP_WEB_REFRESH_COOLDOWN_SECONDS = Math.max(10, config.getInt(
            "settings.create-map.web-refresh-cooldown-seconds",
            CREATE_MAP_WEB_REFRESH_COOLDOWN_SECONDS
        ));
    }

    public static void setCreateMapEnabled(final boolean enabled) {
        CREATE_MAP_ENABLED = enabled;
        config.set("settings.create-map.enabled", enabled);
        config.save();
    }

    public static boolean UI_COORDINATES_ENABLED = true;
    public static boolean UI_LINK_ENABLED = true;
    public static String UI_SIDEBAR_PINNED = "unpinned";

    private static void uiSettings() {
        UI_COORDINATES_ENABLED = config.getBoolean("settings.ui.coordinates.enabled", UI_COORDINATES_ENABLED);
        UI_LINK_ENABLED = config.getBoolean("settings.ui.link.enabled", UI_LINK_ENABLED);
        UI_SIDEBAR_PINNED = config.getString("settings.ui.sidebar.pinned", UI_SIDEBAR_PINNED);
    }

    public static String MAIN_COMMAND_LABEL = "squaremap";
    public static final List<String> MAIN_COMMAND_ALIASES = new ArrayList<>();

    private static void commandSettings() {
        MAIN_COMMAND_LABEL = config.getString("settings.commands.main-command-label", MAIN_COMMAND_LABEL);
        MAIN_COMMAND_ALIASES.clear();
        MAIN_COMMAND_ALIASES.addAll(config.getList(String.class, "settings.commands.main-command-aliases", List.of("map")));
    }

    public static void toggleProgressLogging() {
        PROGRESS_LOGGING = !PROGRESS_LOGGING;
        config.set("settings.render-progress-logging.enabled", PROGRESS_LOGGING);
        config.save();
    }

    public static void setLoggingInterval(final int rate) {
        PROGRESS_LOGGING_INTERVAL = rate;
        config.set("settings.render-progress-logging.interval-seconds", rate);
        config.save();
    }

    public static volatile boolean PROGRESS_LOGGING;
    public static volatile int PROGRESS_LOGGING_INTERVAL;

    private static void progressLogging() {
        PROGRESS_LOGGING = config.getBoolean("settings.render-progress-logging.enabled", true);
        PROGRESS_LOGGING_INTERVAL = config.getInt("settings.render-progress-logging.interval-seconds", 1);
    }

    public static Config config() {
        return config;
    }

}
