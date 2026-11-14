package com.serverhub.network;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Rebuilds {@link ClientboundCommandsPacket} for cross-version clients (such as 26.1.2 via
 * ViaVersion) with non-vanilla NeoForge argument types replaced by {@link StringArgumentType#word()}
 * so ViaVersion's {@code CommandRewriter} never encounters NeoForge's {@code -256} modded marker.
 */
public final class CommandTreeSanitizer {
    private CommandTreeSanitizer() {}

    public static ClientboundCommandsPacket buildSanitizedPacket(
            RootCommandNode<CommandSourceStack> sourceRoot, CommandSourceStack source) {
        RootCommandNode<SharedSuggestionProvider> clientRoot = new RootCommandNode<>();
        Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> map =
                new IdentityHashMap<>();
        map.put(sourceRoot, clientRoot);
        fillUsable(sourceRoot, clientRoot, source, map);
        return new ClientboundCommandsPacket(clientRoot);
    }

    private static void fillUsable(
            CommandNode<CommandSourceStack> sourceNode,
            CommandNode<SharedSuggestionProvider> targetNode,
            CommandSourceStack source,
            Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> map) {
        for (CommandNode<CommandSourceStack> child : sourceNode.getChildren()) {
            if (!child.canUse(source)) {
                continue;
            }
            ArgumentBuilder<SharedSuggestionProvider, ?> builder = toClientBuilder(child);
            if (builder == null) {
                continue;
            }
            builder.requires(s -> true);
            if (child.getCommand() != null) {
                builder.executes(ctx -> 0);
            }
            if (child.getRedirect() != null) {
                CommandNode<SharedSuggestionProvider> redirectTarget = map.get(child.getRedirect());
                if (redirectTarget != null) {
                    builder.redirect(redirectTarget);
                }
            }
            CommandNode<SharedSuggestionProvider> built = builder.build();
            map.put(child, built);
            targetNode.addChild(built);
            if (!child.getChildren().isEmpty()) {
                fillUsable(child, built, source, map);
            }
        }
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentBuilder<SharedSuggestionProvider, ?> toClientBuilder(
            CommandNode<CommandSourceStack> node) {
        if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
            return LiteralArgumentBuilder.literal(literal.getLiteral());
        }
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> arg) {
            ArgumentType<?> safeType =
                    isVanillaArgumentType(arg.getType())
                            ? arg.getType()
                            : StringArgumentType.word();
            RequiredArgumentBuilder<SharedSuggestionProvider, ?> builder =
                    RequiredArgumentBuilder.argument(arg.getName(), safeType);
            SuggestionProvider<?> suggestions = arg.getCustomSuggestions();
            if (suggestions != null) {
                ResourceLocation sugName =
                        SuggestionProviders.getName(
                                (SuggestionProvider<SharedSuggestionProvider>) suggestions);
                if ("minecraft".equals(sugName.getNamespace())) {
                    builder.suggests((SuggestionProvider<SharedSuggestionProvider>) suggestions);
                } else {
                    builder.suggests(SuggestionProviders.ASK_SERVER);
                }
            }
            return builder;
        }
        return null;
    }

    public static boolean isVanillaArgumentType(ArgumentType<?> type) {
        try {
            ArgumentTypeInfo<?, ?> info = ArgumentTypeInfos.byClass(type);
            ResourceLocation key = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(info);
            return key != null && "minecraft".equals(key.getNamespace());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
