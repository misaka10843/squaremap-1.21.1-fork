package xyz.jpenilla.squaremap.common.command.commands;

import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import org.incendo.cloud.context.CommandContext;
import xyz.jpenilla.squaremap.common.WorldManager;
import xyz.jpenilla.squaremap.common.command.Commander;
import xyz.jpenilla.squaremap.common.command.Commands;
import xyz.jpenilla.squaremap.common.command.SquaremapCommand;
import xyz.jpenilla.squaremap.common.data.MapWorldInternal;

import static xyz.jpenilla.squaremap.common.command.argument.parser.LevelParser.levelParser;

/** Explicit, asynchronous cleanup and format migration for existing tiles. */
public final class TileMaintenanceCommand extends SquaremapCommand {
    private final WorldManager worldManager;

    @Inject
    private TileMaintenanceCommand(final Commands commands, final WorldManager worldManager) {
        super(commands);
        this.worldManager = worldManager;
    }

    @Override
    public void register() {
        this.commands.registerSubcommand(builder -> builder.literal("tiles")
            .literal("gc")
            .required("world", levelParser())
            .permission("squaremap.command.tiles")
            .handler(context -> this.run(context, false)));
        this.commands.registerSubcommand(builder -> builder.literal("tiles")
            .literal("migrate")
            .required("world", levelParser())
            .permission("squaremap.command.tiles")
            .handler(context -> this.run(context, true)));
    }

    private void run(final CommandContext<Commander> context, final boolean migrate) {
        final Commander sender = context.sender();
        final ServerLevel level = context.get("world");
        final MapWorldInternal world = this.worldManager.getWorldIfEnabled(level)
            .orElseThrow(() -> new IllegalStateException("Map is not enabled for that world"));
        sender.sendMessage(Component.text(migrate
            ? "Tile migration and cleanup started in the background."
            : "Tile cleanup started in the background."));
        world.compactTiles(migrate).whenComplete((stats, failure) -> {
            final Runnable notify = () -> {
            if (failure != null) {
                sender.sendMessage(Component.text("Tile maintenance failed: " + failure.getMessage()));
            } else {
                sender.sendMessage(Component.text("Tile maintenance complete: deleted=" + stats.deletedFiles()
                    + ", migrated=" + stats.migratedFiles() + ", bytes-freed=" + stats.bytesFreed()));
            }
            };
            level.getServer().execute(notify);
        });
    }
}
