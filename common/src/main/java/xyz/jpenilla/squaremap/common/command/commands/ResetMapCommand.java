package xyz.jpenilla.squaremap.common.command.commands;

import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.server.level.ServerLevel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.processors.confirmation.ConfirmationManager;
import xyz.jpenilla.squaremap.common.WorldManager;
import xyz.jpenilla.squaremap.common.WorldManagerImpl;
import xyz.jpenilla.squaremap.common.httpd.JsonCache;
import xyz.jpenilla.squaremap.common.util.Util;
import xyz.jpenilla.squaremap.common.command.Commander;
import xyz.jpenilla.squaremap.common.command.Commands;
import xyz.jpenilla.squaremap.common.command.SquaremapCommand;
import xyz.jpenilla.squaremap.common.config.Messages;
import xyz.jpenilla.squaremap.common.data.DirectoryProvider;
import xyz.jpenilla.squaremap.common.data.MapWorldInternal;
import xyz.jpenilla.squaremap.common.util.Components;
import xyz.jpenilla.squaremap.common.util.FileUtil;

import static org.incendo.cloud.minecraft.extras.RichDescription.richDescription;
import static xyz.jpenilla.squaremap.common.command.argument.parser.LevelParser.levelParser;

@DefaultQualifier(NonNull.class)
public final class ResetMapCommand extends SquaremapCommand {
    private final DirectoryProvider directoryProvider;
    private final WorldManagerImpl worldManager;
    private final JsonCache jsonCache;

    @Inject
    private ResetMapCommand(
        final Commands commands,
        final DirectoryProvider directoryProvider,
        final WorldManagerImpl worldManager,
        final JsonCache jsonCache
    ) {
        super(commands);
        this.directoryProvider = directoryProvider;
        this.worldManager = worldManager;
        this.jsonCache = jsonCache;
    }

    @Override
    public void register() {
        this.commands.registerSubcommand(builder ->
            builder.literal("resetmap")
                .required("world", levelParser())
                .commandDescription(richDescription(Messages.RESETMAP_COMMAND_DESCRIPTION))
                .meta(ConfirmationManager.META_CONFIRMATION_REQUIRED, true)
                .permission("squaremap.command.resetmap")
                .handler(this::executeResetMap));
    }

    private void executeResetMap(final CommandContext<Commander> context) {
        final Commander sender = context.sender();
        final ServerLevel world = context.get("world");
        final var mapWorld = this.worldManager.getWorldIfEnabled(world);
        if (mapWorld.isPresent() && mapWorld.get().renderScheduler().isRendering()) {
            sender.sendMessage(Messages.RENDER_IN_PROGRESS.withPlaceholders(Components.worldPlaceholder(world)));
            return;
        }
        final Path worldTilesDir = this.directoryProvider.getAndCreateTilesDirectory(world);
        this.worldManager.worldUnloaded(world);
        try {
            FileUtil.deleteContentsRecursively(worldTilesDir);
        } catch (final IOException ex) {
            throw new RuntimeException("Could not reset map for level '" + world.dimension().location() + "'", ex);
        } finally {
            this.jsonCache.clearPrefix("/tiles/" + Util.levelWebName(world) + "/");
            if (mapWorld.isPresent()) this.worldManager.initWorld(world);
        }
        this.worldManager.getWorldIfEnabled(world).ifPresent(MapWorldInternal::didReset);
        sender.sendMessage(Messages.SUCCESSFULLY_RESET_MAP.withPlaceholders(Components.worldPlaceholder(world)));
    }
}
