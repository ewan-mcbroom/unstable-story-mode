package com.unstablestory.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.unstablestory.StoryMode;
import com.unstablestory.vex.VexBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One player playing one episode. Steps run in order; some finish at once
 * (a line of dialogue), some wait (a pause, a fight, an objective).
 *
 * Positions in episode files are offsets from where the player stood when the
 * episode started, until real story locations exist.
 */
public final class EpisodeSession {
    private enum Waiting { NONE, TICKS, FIGHT, OBJECTIVE }

    private final UUID playerId;
    private final Episode episode;
    private final BlockPos anchor;
    private final InventoryBackup backup;
    private final Set<String> aliveBots = new LinkedHashSet<>();
    private final Set<String> allBots = new LinkedHashSet<>();

    private int step = 0;
    private int checkpoint = 0;
    private Waiting waiting = Waiting.NONE;
    private int waitTicks = 0;
    private String objective = "";
    private boolean finished = false;

    EpisodeSession(ServerPlayer player, Episode episode) {
        this.playerId = player.getUUID();
        this.episode = episode;
        this.anchor = player.blockPosition();
        this.backup = InventoryBackup.take(player);
    }

    public Episode episode() { return episode; }
    public int stepIndex() { return step; }
    public boolean finished() { return finished; }
    public boolean ownsBot(String name) { return allBots.contains(name); }

    /** Called every server tick. Runs steps until one has to wait. */
    void tick(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || finished) {
            return;
        }
        if (waiting == Waiting.TICKS && --waitTicks > 0) {
            return;
        }
        if (waiting == Waiting.FIGHT && !aliveBots.isEmpty()) {
            return;
        }
        if (waiting == Waiting.OBJECTIVE) {
            if (server.getTickCount() % 40 == 0) {
                actionBar(server, player, objective);
            }
            return;
        }
        waiting = Waiting.NONE;
        int guard = 0;
        while (!finished && waiting == Waiting.NONE && guard++ < 64) {
            if (step >= episode.steps().size()) {
                finish(server, player, true);
                return;
            }
            JsonObject s = episode.steps().get(step++);
            try {
                run(server, player, s);
            } catch (RuntimeException e) {
                StoryMode.LOGGER.error("Step {} of {} failed: {}", step, episode.id(), s, e);
                player.sendSystemMessage(Component.literal("[Story] Step " + step + " failed, skipping it. See the log.")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private void run(MinecraftServer server, ServerPlayer player, JsonObject s) {
        String type = str(s, "type", "");
        String name = player.getName().getString();
        switch (type) {
            case "title" -> {
                VexBridge.runRaw(server, "title " + name + " times 10 60 20");
                if (s.has("subtitle")) {
                    VexBridge.runRaw(server, "title " + name + " subtitle " + text(str(s, "subtitle", ""), "gray"));
                }
                VexBridge.runRaw(server, "title " + name + " title " + text(str(s, "title", ""), "gold"));
                waitFor(int_(s, "ticks", 80));
            }
            case "say" -> {
                ChatFormatting color = ChatFormatting.getByName(str(s, "color", "white"));
                Component line = Component.literal("<").append(Component.literal(str(s, "speaker", "?"))
                                .withStyle(color == null ? ChatFormatting.WHITE : color))
                        .append(Component.literal("> " + str(s, "text", "")));
                player.sendSystemMessage(line);
                waitFor(int_(s, "ticks", 50));
            }
            case "narrate" -> {
                player.sendSystemMessage(Component.literal(str(s, "text", ""))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                waitFor(int_(s, "ticks", 60));
            }
            case "wait" -> waitFor(int_(s, "ticks", 20));
            case "teleport" -> {
                BlockPos p = pos(s);
                VexBridge.runRaw(server, "tp " + name + " " + p.getX() + " " + p.getY() + " " + p.getZ());
            }
            case "effect" -> VexBridge.runRaw(server, "effect give " + name + " " + str(s, "effect", "")
                    + " " + int_(s, "seconds", 999999) + " " + int_(s, "amplifier", 0) + " true");
            case "clear_effects" -> VexBridge.runRaw(server, "effect clear " + name);
            case "player_kit" -> {
                player.getInventory().clearContent();
                VexBridge.giveKit(server, name, str(s, "kit", ""));
            }
            case "spawn" -> {
                for (JsonElement e : s.getAsJsonArray("bots")) {
                    spawnBot(server, e.getAsJsonObject());
                }
            }
            case "fight" -> {
                if (s.has("text")) {
                    objective = str(s, "text", "");
                    actionBar(server, player, objective);
                }
                if (!aliveBots.isEmpty()) {
                    waiting = Waiting.FIGHT;
                }
            }
            case "despawn_all" -> removeAllBots(server);
            case "objective" -> {
                objective = str(s, "text", "");
                player.sendSystemMessage(Component.literal("Objective: " + objective).withStyle(ChatFormatting.YELLOW));
                actionBar(server, player, objective);
                waiting = Waiting.OBJECTIVE;
            }
            case "checkpoint" -> {
                checkpoint = step;
                player.sendSystemMessage(Component.literal("Checkpoint saved.").withStyle(ChatFormatting.GREEN));
            }
            case "command" -> VexBridge.runRaw(server, str(s, "command", "").replace("{player}", name));
            case "note" -> { /* a comment for whoever edits the file; does nothing in game */ }
            default -> StoryMode.LOGGER.warn("Unknown step type '{}' in {}", type, episode.id());
        }
    }

    private void spawnBot(MinecraftServer server, JsonObject b) {
        String botName = str(b, "name", "Bot");
        BlockPos p = pos(b);
        VexBridge.spawnOnTeam(server, str(b, "team", "story"), botName, str(b, "kit", ""), p.getX(), p.getY(), p.getZ());
        if (b.has("skin")) {
            VexBridge.setSkin(server, botName, str(b, "skin", botName));
        }
        if (b.has("wander")) {
            VexBridge.wander(server, botName, int_(b, "wander", 8), p.getX(), p.getY(), p.getZ());
        }
        allBots.add(botName);
        if (!b.has("hostile") || b.get("hostile").getAsBoolean()) {
            aliveBots.add(botName);
        }
    }

    /** A bot this session spawned has died. */
    void onBotDied(String botName) {
        aliveBots.remove(botName);
    }

    /** "/story next": finishes the current objective. Used for beats not built yet. */
    boolean completeObjective() {
        if (waiting != Waiting.OBJECTIVE) {
            return false;
        }
        waiting = Waiting.NONE;
        return true;
    }

    /** The player would have died: undo it and go back to the last checkpoint. */
    void onPlayerDefeated(MinecraftServer server, ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        VexBridge.runRaw(server, "effect clear " + player.getName().getString());
        removeAllBots(server);
        player.sendSystemMessage(Component.literal("You were defeated. Back to the last checkpoint.")
                .withStyle(ChatFormatting.RED));
        restartFrom(checkpoint);
    }

    void restartFrom(int index) {
        step = index;
        waiting = Waiting.TICKS;
        waitTicks = 40;
    }

    int checkpoint() { return checkpoint; }

    void finish(MinecraftServer server, ServerPlayer player, boolean completed) {
        if (finished) {
            return;
        }
        finished = true;
        removeAllBots(server);
        if (player != null) {
            VexBridge.runRaw(server, "effect clear " + player.getName().getString());
            backup.restore(player);
            player.sendSystemMessage(Component.literal(completed
                    ? "Episode complete: " + episode.name()
                    : "Episode stopped. Your items are back.").withStyle(ChatFormatting.GOLD));
        }
    }

    private void removeAllBots(MinecraftServer server) {
        for (String bot : allBots) {
            VexBridge.remove(server, bot);
        }
        allBots.clear();
        aliveBots.clear();
    }

    private void waitFor(int ticks) {
        if (ticks > 0) {
            waiting = Waiting.TICKS;
            waitTicks = ticks;
        }
    }

    private BlockPos pos(JsonObject s) {
        return anchor.offset(int_(s, "x", 0), int_(s, "y", 0), int_(s, "z", 0));
    }

    private static void actionBar(MinecraftServer server, ServerPlayer player, String message) {
        VexBridge.runRaw(server, "title " + player.getName().getString() + " actionbar " + text(message, "yellow"));
    }

    private static String text(String message, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("text", message);
        o.addProperty("color", color);
        return o.toString();
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static int int_(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }
}
