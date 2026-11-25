package com.unstablestory.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.unstablestory.StoryMode;
import com.unstablestory.vex.VexApi;
import com.unstablestory.vex.VexBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player playing one episode. Steps run in order; some finish at once
 * (a line of dialogue), some wait (a pause, a fight, an objective).
 *
 * Positions in episode files are offsets from where the player stood when the
 * episode started, until real story locations exist.
 */
public final class EpisodeSession {
    private enum Waiting { NONE, TICKS, FIGHT, OBJECTIVE, ESCAPE }

    private final UUID playerId;
    private final Episode episode;
    private BlockPos anchor;
    private final InventoryBackup backup;
    private final List<StoryBot> bots = new ArrayList<>();

    private int step = 0;
    private int checkpoint = 0;
    private Waiting waiting = Waiting.NONE;
    private int waitTicks = 0;
    private String objective = "";
    private int fightUntilRemaining = 0;
    private long fightDeadline = -1;
    private double escapeDistance = 0;
    private long escapeDeadline = -1;
    private boolean finished = false;
    private MinecraftServer server;

    EpisodeSession(ServerPlayer player, Episode episode) {
        this.playerId = player.getUUID();
        this.episode = episode;
        this.anchor = player.blockPosition();
        this.backup = InventoryBackup.take(player);
    }

    public Episode episode() { return episode; }
    public int stepIndex() { return step; }
    public boolean finished() { return finished; }

    /** The story bot that is this entity, if this session spawned it. */
    StoryBot findBot(UUID entity) {
        // Only living bots: VexBot reuses the real account's UUID when a name is spawned again.
        for (StoryBot b : bots) {
            if (b.alive && b.isEntity(entity)) {
                return b;
            }
        }
        return null;
    }

    private boolean hostileBotsAlive() {
        return hostileBotsAliveCount() > fightUntilRemaining;
    }

    private int hostileBotsAliveCount() {
        int n = 0;
        for (StoryBot b : bots) {
            if (b.hostile && b.alive) {
                n++;
            }
        }
        return n;
    }

    private StoryBot bot(String requestedName) {
        // The newest living bot with that name.
        for (int i = bots.size() - 1; i >= 0; i--) {
            StoryBot b = bots.get(i);
            if (b.alive && (b.requestedName.equalsIgnoreCase(requestedName) || b.name().equalsIgnoreCase(requestedName))) {
                return b;
            }
        }
        return null;
    }

    /** True once every enemy is far enough away (or time is up). */
    private boolean escaped(MinecraftServer server, ServerPlayer player) {
        if (escapeDeadline >= 0 && server.getTickCount() >= escapeDeadline) {
            return true;
        }
        for (StoryBot b : bots) {
            if (!b.hostile || !b.alive || !b.found()) {
                continue;
            }
            ServerPlayer e = server.getPlayerList().getPlayer(b.uuid);
            if (e != null && e.distanceTo(player) < escapeDistance) {
                return false;
            }
        }
        return true;
    }

    /** Match bots that have just appeared to the player entities VexBot made for them. */
    private void findNewBots(MinecraftServer server, ServerPlayer player) {
        for (StoryBot b : bots) {
            if (b.found() || !b.alive) {
                continue;
            }
            for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
                if (b.matches(candidate) && !StoryManager.isTrackedBot(candidate.getUUID())) {
                    b.uuid = candidate.getUUID();
                    b.actualName = candidate.getName().getString();
                    setUpBot(server, player, b);
                    break;
                }
            }
            if (!b.found() && ++b.ticksWaiting > 100) {
                b.alive = false;
                player.sendSystemMessage(Component.literal("[Story] " + b.requestedName
                        + " didn't spawn. Try /story debug on and /story retry.").withStyle(ChatFormatting.RED));
            }
        }
    }

    private void setUpBot(MinecraftServer server, ServerPlayer player, StoryBot b) {
        String me = player.getName().getString();
        if (VexApi.available()) {
            // The kit was handed over at spawn. Now set who the bot fights.
            if (b.hostile) {
                VexApi.team("story_enemies", b.name(), "SPECIFIC", true, List.of(me), List.of(), List.of());
                VexApi.hunt(b.name(), me);
            } else if (b.ally) {
                VexApi.team("story_allies", b.name(), "SPECIFIC_TEAMS", false, List.of(), List.of("story_enemies"), List.of(me));
                if (b.follow) {
                    VexApi.follow(b.name(), me);
                }
            } else {
                VexApi.team("story_peaceful", b.name(), "NONE", false, List.of(), List.of(), List.of());
            }
            VexBridge.debug(server, b.requestedName + " appeared as " + b.name()
                    + (b.hostile ? " (enemy)" : b.ally ? " (ally)" : " (peaceful)"));
        } else if (!b.kit.isEmpty()) {
            VexBridge.giveKit(server, b.name(), b.kit);
        }
        if (b.skin != null) {
            VexBridge.setSkin(server, b.name(), b.skin);
        }
        if (b.wander > 0) {
            VexBridge.wander(server, b.name(), b.wander, b.x, b.y, b.z);
        }
    }

    /** Called every server tick. Runs steps until one has to wait. */
    void tick(MinecraftServer server) {
        this.server = server;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || finished) {
            return;
        }
        findNewBots(server, player);
        if (waiting == Waiting.TICKS && --waitTicks > 0) {
            return;
        }
        if (waiting == Waiting.FIGHT && hostileBotsAlive()
                && (fightDeadline < 0 || server.getTickCount() < fightDeadline)) {
            return;
        }
        if (waiting == Waiting.ESCAPE && !escaped(server, player)) {
            if (server.getTickCount() % 40 == 0) {
                actionBar(server, player, objective);
            }
            return;
        }
        fightUntilRemaining = 0;
        fightDeadline = -1;
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
                // Optional: stop when only this many enemies are left, or after this many seconds.
                fightUntilRemaining = int_(s, "until_remaining", 0);
                fightDeadline = s.has("max_seconds") ? server.getTickCount() + 20L * int_(s, "max_seconds", 0) : -1;
                if (hostileBotsAlive()) {
                    waiting = Waiting.FIGHT;
                }
            }
            case "escape" -> {
                objective = str(s, "text", "Get away!");
                player.sendSystemMessage(Component.literal("Objective: " + objective).withStyle(ChatFormatting.YELLOW));
                escapeDistance = int_(s, "distance", 48);
                escapeDeadline = s.has("seconds") ? server.getTickCount() + 20L * int_(s, "seconds", 0) : -1;
                waiting = Waiting.ESCAPE;
            }
            case "player_command" -> VexBridge.runRaw(server, "execute as " + name + " at " + name + " run "
                    + str(s, "command", "").replace("{player}", name));
            case "set_anchor" -> {
                anchor = player.blockPosition();
                VexBridge.debug(server, "Story anchor moved to " + anchor.toShortString());
            }
            case "switch_side" -> {
                StoryBot b = bot(str(s, "bot", ""));
                if (b != null) {
                    String side = str(s, "to", "ally");
                    b.hostile = side.equals("enemy");
                    b.ally = side.equals("ally");
                    if (b.found()) {
                        setUpBot(server, player, b);
                    }
                }
            }
            case "despawn" -> {
                for (JsonElement e : s.getAsJsonArray("bots")) {
                    StoryBot b = bot(e.getAsString());
                    if (b != null) {
                        VexBridge.remove(server, b.name());
                        b.alive = false;
                    }
                }
            }
            case "despawn_enemies" -> {
                for (StoryBot b : bots) {
                    if (b.hostile && b.alive) {
                        VexBridge.remove(server, b.name());
                        b.alive = false;
                    }
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
        BlockPos p = pos(b);
        boolean ally = b.has("ally") && b.get("ally").getAsBoolean();
        boolean hostile = !ally && (!b.has("hostile") || b.get("hostile").getAsBoolean());
        StoryBot bot = new StoryBot(str(b, "name", "Bot"), b.has("skin") ? str(b, "skin", "") : null,
                str(b, "kit", ""), hostile, ally, b.has("follow") && b.get("follow").getAsBoolean(), int_(b, "wander", 0), p.getX(), p.getY(), p.getZ(),
                StoryBot.currentPlayers(server));
        bots.add(bot);
        VexBridge.spawnOnTeam(server, str(b, "team", "story"), bot.requestedName, bot.kit, p.getX(), p.getY(), p.getZ());
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            findNewBots(server, player);
        }
    }

    /** A bot this session spawned has died. */
    void onBotDied(StoryBot bot) {
        bot.alive = false;
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
        for (StoryBot bot : bots) {
            VexBridge.remove(server, bot.name());
        }
        bots.clear();
    }

    private void waitFor(int ticks) {
        if (ticks > 0) {
            waiting = Waiting.TICKS;
            waitTicks = ticks;
        }
    }

    /** A position from the episode file, as an offset from the anchor. Without "y" it is the ground there. */
    private BlockPos pos(JsonObject s) {
        int x = anchor.getX() + int_(s, "x", 0);
        int z = anchor.getZ() + int_(s, "z", 0);
        if (s.has("y")) {
            return new BlockPos(x, anchor.getY() + int_(s, "y", 0), z);
        }
        return new BlockPos(x, surfaceY(x, z), z);
    }

    private int surfaceY(int x, int z) {
        if (server == null) {
            return anchor.getY();
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return anchor.getY();
        }
        Level level = player.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y > level.getMinY() ? y : anchor.getY();
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
