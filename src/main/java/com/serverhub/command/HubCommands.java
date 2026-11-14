package com.serverhub.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.serverhub.ServerHubMod;
import com.serverhub.chat.ChatMuteManager;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.relay.ServerStatusInfo;
import com.serverhub.transfer.TransferManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class HubCommands {
    private static final SuggestionProvider<CommandSourceStack> SERVER_SUGGESTIONS = (ctx, builder) -> {
        Collection<String> ids = ServerHubMod.getKnownServers().keySet();
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> MUTE_SERVER_SUGGESTIONS = (ctx, builder) -> {
        List<String> options = new java.util.ArrayList<>(ServerHubMod.getKnownServers().keySet());
        options.add("all");
        return SharedSuggestionProvider.suggest(options, builder);
    };

    private HubCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hub").executes(HubCommands::cmdHub));

        dispatcher.register(Commands.literal("join")
                .then(Commands.argument("serverId", StringArgumentType.word())
                        .suggests(SERVER_SUGGESTIONS)
                        .executes(HubCommands::cmdJoin)));

        dispatcher.register(Commands.literal("back").executes(HubCommands::cmdBack));

        dispatcher.register(Commands.literal("servers").executes(HubCommands::cmdServers));

        dispatcher.register(Commands.literal("hubchat")
                .then(Commands.literal("local").executes(ctx -> setLocalMode(ctx, true)))
                .then(Commands.literal("global").executes(ctx -> setLocalMode(ctx, false)))
                .then(Commands.literal("mute")
                        .then(Commands.argument("serverId", StringArgumentType.word())
                                .suggests(MUTE_SERVER_SUGGESTIONS)
                                .executes(HubCommands::cmdMute)))
                .then(Commands.literal("unmute")
                        .then(Commands.argument("serverId", StringArgumentType.word())
                                .suggests(MUTE_SERVER_SUGGESTIONS)
                                .executes(HubCommands::cmdUnmute)))
                .then(Commands.literal("status").executes(HubCommands::cmdChatStatus)));

        dispatcher.register(Commands.literal("hubadmin")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reload").executes(HubCommands::cmdAdminReload))
                .then(Commands.literal("addportal")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("targetServerId", StringArgumentType.word())
                                        .suggests(SERVER_SUGGESTIONS)
                                        .then(Commands.argument("x1", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y1", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z1", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("x2", DoubleArgumentType.doubleArg())
                                                                        .then(Commands.argument("y2", DoubleArgumentType.doubleArg())
                                                                                .then(Commands.argument("z2", DoubleArgumentType.doubleArg())
                                                                                        .executes(HubCommands::cmdAdminAddPortal))))))))))
                .then(Commands.literal("removeportal")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(HubCommands::cmdAdminRemovePortal)))
                .then(Commands.literal("restart").executes(HubCommands::cmdAdminRestart)));
    }

    private static int cmdHub(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TransferManager.transferPlayerToHub(player);
        return 1;
    }

    private static int cmdJoin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String serverId = StringArgumentType.getString(ctx, "serverId");
        TransferManager.transferPlayerToServer(player, serverId);
        return 1;
    }

    private static int cmdBack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<String> origin = ClientModTracker.getOriginServer(player.getUUID());
        if (origin.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No previous server recorded."));
            return 0;
        }
        TransferManager.transferPlayerToServer(player, origin.get());
        return 1;
    }

    private static int cmdServers(CommandContext<CommandSourceStack> ctx) {
        Collection<ServerStatusInfo> servers = ServerHubMod.getKnownServers().values();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (servers.isEmpty()) {
            HubConfig cfg = HubConfig.get();
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[" + cfg.serverId + "] " + cfg.serverDisplayName + " - ONLINE"),
                    false);
            return 1;
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("Connected Servers:").withStyle(ChatFormatting.GOLD),
                false);
        for (ServerStatusInfo info : servers) {
            boolean online = info.isOnline();
            String stateText = online ? "ONLINE" : info.status();
            ChatFormatting stateColor = online
                    ? ChatFormatting.GREEN
                    : ("RESTARTING".equalsIgnoreCase(info.status()) ? ChatFormatting.YELLOW : ChatFormatting.RED);

            MutableComponent line = Component.literal("[" + info.serverId() + "] ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(info.displayName() + " - ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(stateText).withStyle(stateColor))
                    .append(Component.literal(" (" + info.onlinePlayers() + "/" + info.maxPlayers() + ")")
                            .withStyle(ChatFormatting.GRAY));

            if (player != null) {
                List<String> missing = ClientModTracker.getMissingMods(
                        player.getUUID(), info.serverId(), info.requiredMods());
                if (missing.isEmpty()) {
                    line.append(Component.literal(" [Compatible]").withStyle(ChatFormatting.AQUA));
                } else {
                    line.append(Component.literal(" [Missing: " + String.join(", ", missing) + "]")
                            .withStyle(ChatFormatting.RED));
                }
            }
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int setLocalMode(CommandContext<CommandSourceStack> ctx, boolean localOnly)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (localOnly) {
            ChatMuteManager.setLocalOnly(player.getUUID(), true);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Cross-server chat muted. Showing this server only.")
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        } else {
            ChatMuteManager.clearMutes(player.getUUID());
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Cross-server chat enabled for all servers.")
                            .withStyle(ChatFormatting.GREEN),
                    false);
        }
        return 1;
    }

    private static int cmdMute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String serverId = StringArgumentType.getString(ctx, "serverId");
        if ("all".equalsIgnoreCase(serverId)) {
            return setLocalMode(ctx, true);
        }
        ChatMuteManager.muteServer(player.getUUID(), serverId);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Muted chat from server: " + serverId).withStyle(ChatFormatting.YELLOW),
                false);
        return 1;
    }

    private static int cmdUnmute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String serverId = StringArgumentType.getString(ctx, "serverId");
        if ("all".equalsIgnoreCase(serverId)) {
            return setLocalMode(ctx, false);
        }
        ChatMuteManager.unmuteServer(player.getUUID(), serverId);
        ChatMuteManager.PlayerMuteSettings settings = ChatMuteManager.getSettings(player.getUUID());
        String msg = settings.localOnly
                ? "Unmuted " + serverId + ". Local mode is still on; use /hubchat global to hear other servers."
                : "Unmuted chat from server: " + serverId;
        ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int cmdChatStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ChatMuteManager.PlayerMuteSettings settings = ChatMuteManager.getSettings(player.getUUID());
        if (settings.localOnly) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Chat mode: local only (other servers muted).")
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        } else if (settings.mutedServers.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Chat mode: global (no servers muted).")
                            .withStyle(ChatFormatting.GREEN),
                    false);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Chat mode: global. Muted servers: "
                                    + String.join(", ", settings.mutedServers))
                            .withStyle(ChatFormatting.AQUA),
                    false);
        }
        return 1;
    }

    private static int cmdAdminReload(CommandContext<CommandSourceStack> ctx) {
        HubConfig.reload();
        ChatMuteManager.load();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Server Hub configuration reloaded.").withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int cmdAdminAddPortal(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String target = StringArgumentType.getString(ctx, "targetServerId");
        double x1 = DoubleArgumentType.getDouble(ctx, "x1");
        double y1 = DoubleArgumentType.getDouble(ctx, "y1");
        double z1 = DoubleArgumentType.getDouble(ctx, "z1");
        double x2 = DoubleArgumentType.getDouble(ctx, "x2");
        double y2 = DoubleArgumentType.getDouble(ctx, "y2");
        double z2 = DoubleArgumentType.getDouble(ctx, "z2");
        String dim = ctx.getSource().getLevel().dimension().location().toString();

        HubConfig cfg = HubConfig.get();
        cfg.portals.removeIf(p -> name.equalsIgnoreCase(p.name));
        cfg.portals.add(new HubConfig.PortalDefinition(name, target, dim, x1, y1, z1, x2, y2, z2));
        HubConfig.save();

        ctx.getSource().sendSuccess(
                () -> Component.literal("Added portal '" + name + "' for server '" + target + "' in " + dim + ".")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int cmdAdminRemovePortal(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        HubConfig cfg = HubConfig.get();
        boolean removed = cfg.portals.removeIf(p -> name.equalsIgnoreCase(p.name));
        if (!removed) {
            ctx.getSource().sendFailure(Component.literal("No portal found named '" + name + "'."));
            return 0;
        }
        HubConfig.save();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Removed portal '" + name + "'.").withStyle(ChatFormatting.YELLOW),
                true);
        return 1;
    }

    private static int cmdAdminRestart(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Evacuating players to Hub and restarting server...")
                        .withStyle(ChatFormatting.YELLOW),
                true);
        TransferManager.evacuatePlayersToHubOnShutdown(server);
        server.halt(false);
        return 1;
    }
}
