package com.unstablestory.story;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.unstablestory.config.StoryConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /story list               show the episodes
 * /story start <episode>    start an episode (your items are saved and given back after)
 * /story next               finish the current objective (for beats not built yet)
 * /story retry              go back to the last checkpoint
 * /story stop               leave the episode
 * /story reload             reload episodes and the VexBot command file
 */
public final class StoryCommands {
    private StoryCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("story")
                .then(Commands.literal("list").executes(StoryCommands::list))
                .then(Commands.literal("start")
                        .then(Commands.argument("episode", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Episode.all().keySet(), builder))
                                .executes(StoryCommands::start)))
                .then(Commands.literal("next").executes(StoryCommands::next))
                .then(Commands.literal("retry").executes(StoryCommands::retry))
                .then(Commands.literal("stop").executes(StoryCommands::stop))
                .then(Commands.literal("reload").executes(StoryCommands::reload)));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (Episode.all().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No episodes loaded."), false);
            return 0;
        }
        for (Episode e : Episode.all().values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(e.id()).withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  " + e.name() + " (" + e.protagonist() + ")").withStyle(ChatFormatting.GRAY)), false);
        }
        return Episode.all().size();
    }

    private static int start(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = StringArgumentType.getString(ctx, "episode");
        Episode episode = Episode.get(id);
        if (episode == null) {
            ctx.getSource().sendFailure(Component.literal("No episode called " + id + ". Try /story list."));
            return 0;
        }
        StoryManager.start(ctx.getSource().getServer(), player, episode);
        ctx.getSource().sendSuccess(() -> Component.literal("Starting " + episode.name()
                + ". Your items are saved and come back when the episode ends.").withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int next(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        EpisodeSession session = StoryManager.get(ctx.getSource().getPlayerOrException());
        if (session == null || !session.completeObjective()) {
            ctx.getSource().sendFailure(Component.literal("There is no objective to skip right now."));
            return 0;
        }
        return 1;
    }

    private static int retry(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EpisodeSession session = StoryManager.get(player);
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal("You are not in an episode."));
            return 0;
        }
        session.onPlayerDefeated(ctx.getSource().getServer(), player);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!StoryManager.stop(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException())) {
            ctx.getSource().sendFailure(Component.literal("You are not in an episode."));
            return 0;
        }
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        StoryConfig.load();
        Episode.loadAll();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded " + Episode.all().size()
                + " episode(s) and the VexBot commands."), false);
        return 1;
    }
}
