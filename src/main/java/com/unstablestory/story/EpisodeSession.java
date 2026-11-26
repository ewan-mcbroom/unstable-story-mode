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
    private enum Waiting { NONE, TICKS, FIGHT, OBJECTIVE, ESCAPE, HIT }

    private final UUID playerId;
    private final Episode episode;
    private BlockPos anchor;
    private float anchorYaw;
    private final InventoryBackup backup;
    private final List<StoryBot> bots = new ArrayList<>();

    private int step = 0;
    private int checkpoint = 0;
    private Waiting waiting = Waiting.NONE;
    private int waitTicks = 0;
    private String objective = "";
    private String partTitle = "";
    private int partNumber = 0;
    private boolean hudShown = false;
    private String hitTarget = "";
    private long barrageUntil = -1;
    private int barrageEvery = 60;
    private int barrageCount = 2;
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
        this.anchorYaw = player.getYRot();
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
        if (!hudShown || server.getTickCount() % 20 == 0) {
            updateHud(server, player);
        }
        if (barrageUntil >= 0) {
            if (server.getTickCount() >= barrageUntil) {
                barrageUntil = -1;
            } else if (server.getTickCount() % barrageEvery == 0) {
                orbitalStrike(server, player);
            }
        }
        if (waiting == Waiting.HIT) {
            return;
        }
        if (waiting == Waiting.TICKS && --waitTicks > 0) {
            return;
        }
        if (waiting == Waiting.FIGHT && hostileBotsAlive()
                && (fightDeadline < 0 || server.getTickCount() < fightDeadline)) {
            return;
        }
        if (waiting == Waiting.ESCAPE && !escaped(server, player)) {
            return;
        }
        fightUntilRemaining = 0;
        fightDeadline = -1;
        if (waiting == Waiting.OBJECTIVE) {
            return;
        }
        if (waiting == Waiting.FIGHT || waiting == Waiting.ESCAPE) {
            objectiveDone(server, player);
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
                Component line = Component.literal(str(s, "speaker", "?"))
                                .withStyle(color == null ? ChatFormatting.WHITE : color, ChatFormatting.BOLD)
                        .append(Component.literal(": " + str(s, "text", "")).withStyle(ChatFormatting.WHITE));
                player.sendSystemMessage(line);
                waitFor(int_(s, "ticks", 70));
            }
            case "narrate" -> {
                player.sendSystemMessage(Component.literal("  " + str(s, "text", ""))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                waitFor(int_(s, "ticks", 80));
            }
            case "part" -> {
                // A new part of the episode: big title, a header in chat, the bar at the top updates.
                partNumber = int_(s, "number", partNumber + 1);
                partTitle = str(s, "title", "");
                objective = "";
                String heading = "Part " + partNumber + " of " + parts() + ": " + partTitle;
                player.sendSystemMessage(Component.literal(""));
                player.sendSystemMessage(Component.literal("== " + heading + " ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                VexBridge.runRaw(server, "title " + name + " times 10 50 20");
                VexBridge.runRaw(server, "title " + name + " subtitle " + text(partTitle, "yellow"));
                VexBridge.runRaw(server, "title " + name + " title " + text("Part " + partNumber, "gold"));
                VexBridge.runRaw(server, "playsound minecraft:ui.toast.challenge_complete master " + name);
                updateHud(server, player);
                waitFor(int_(s, "ticks", 70));
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
                    newObjective(server, player, str(s, "text", ""));
                }
                // Optional: stop when only this many enemies are left, or after this many seconds.
                fightUntilRemaining = int_(s, "until_remaining", 0);
                fightDeadline = s.has("max_seconds") ? server.getTickCount() + 20L * int_(s, "max_seconds", 0) : -1;
                if (hostileBotsAlive()) {
                    waiting = Waiting.FIGHT;
                }
            }
            case "escape" -> {
                newObjective(server, player, str(s, "text", "Get away!"));
                escapeDistance = int_(s, "distance", 48);
                escapeDeadline = s.has("seconds") ? server.getTickCount() + 20L * int_(s, "seconds", 0) : -1;
                waiting = Waiting.ESCAPE;
            }
            case "player_command" -> VexBridge.runRaw(server, "execute as " + name + " at " + name + " run "
                    + str(s, "command", "").replace("{player}", name));
            case "set_anchor" -> {
                anchor = player.blockPosition();
                anchorYaw = player.getYRot();
                VexBridge.debug(server, "Story anchor moved to " + anchor.toShortString());
            }
            case "find_border" -> {
                // Stand on top of the terrain at the edge of a biome (e.g. the Spikelands), facing into it.
                int search = int_(s, "search", 160);
                // Generate up to 64 blocks around; further out, only already-loaded ground is searched.
                ScenePlacer.loadArea(player.level(), player.blockPosition(), Math.min(search, 64));
                java.util.Set<String> seen = new java.util.TreeSet<>();
                ScenePlacer.Border border = ScenePlacer.findBorder(player.level(), player.blockPosition(), search,
                        str(s, "biome", "spike"), seen);
                VexBridge.debug(server, "Biomes nearby: " + String.join(", ", seen));
                if (border != null) {
                    anchor = border.feet();
                    anchorYaw = border.yaw();
                    VexBridge.runRaw(server, "tp " + name + " " + (anchor.getX() + 0.5) + " " + anchor.getY() + " "
                            + (anchor.getZ() + 0.5) + " " + anchorYaw + " 0");
                    VexBridge.debug(server, "Scene placed at the " + str(s, "biome", "spike") + " border, " + anchor.toShortString());
                } else {
                    anchor = player.blockPosition();
                    anchorYaw = player.getYRot();
                    VexBridge.debug(server, "No '" + str(s, "biome", "spike") + "' border found; the scene stays where you are");
                }
            }
            case "find_arena" -> {
                // Find open, fairly flat ground for this scene and put the player in the middle of it.
                // "into_farlands": first go this many blocks further into the Far Lands
                // (away from the world centre), and face that way.
                BlockPos from = player.blockPosition();
                float faceYaw = player.getYRot();
                int into = int_(s, "into_farlands", 0);
                if (into > 0) {
                    boolean alongX = Math.abs(from.getX()) >= Math.abs(from.getZ());
                    int dirX = alongX ? Integer.signum(from.getX()) : 0;
                    int dirZ = alongX ? 0 : Integer.signum(from.getZ());
                    from = from.offset(dirX * into, 0, dirZ * into);
                    // Minecraft yaw: 0 faces +Z, 90 faces -X, -90 faces +X, 180 faces -Z.
                    faceYaw = alongX ? (dirX > 0 ? -90f : 90f) : (dirZ > 0 ? 0f : 180f);
                    ScenePlacer.loadArea(player.level(), from, Math.min(int_(s, "search", 48), 64));
                }
                BlockPos arena = ScenePlacer.findArena(player.level(), from,
                        int_(s, "search", 48), int_(s, "size", 16), int_(s, "max_step", 3));
                if (arena != null) {
                    anchorYaw = faceYaw;
                    VexBridge.runRaw(server, "tp " + name + " " + (arena.getX() + 0.5) + " " + arena.getY() + " "
                            + (arena.getZ() + 0.5) + " " + anchorYaw + " 0");
                    anchor = arena;
                    VexBridge.debug(server, "Scene placed at " + arena.toShortString());
                } else {
                    anchor = player.blockPosition();
                    anchorYaw = player.getYRot();
                    VexBridge.debug(server, "No open ground found nearby; the scene stays where you are");
                }
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
                // "killed": true means they die in the story (plays the death sound).
                if (s.has("killed") && s.get("killed").getAsBoolean()) {
                    deathSound(server);
                }
                for (JsonElement e : s.getAsJsonArray("bots")) {
                    StoryBot b = bot(e.getAsString());
                    if (b != null) {
                        VexBridge.remove(server, b.name());
                        b.alive = false;
                    }
                }
            }
            case "hit" -> {
                // Wait until the player hits this character (it won't take damage if it's peaceful).
                hitTarget = str(s, "bot", "");
                newObjective(server, player, str(s, "text", "Hit " + hitTarget));
                waiting = Waiting.HIT;
            }
            case "barrage" -> {
                // Orbital strikes: TNT falls around the player for a while.
                barrageUntil = server.getTickCount() + 20L * int_(s, "seconds", 30);
                barrageEvery = Math.max(10, int_(s, "every_ticks", 60));
                barrageCount = Math.max(1, int_(s, "count", 2));
                player.sendSystemMessage(Component.literal("Orbital strikes incoming!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            case "barrage_stop" -> barrageUntil = -1;
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
                newObjective(server, player, str(s, "text", ""));
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

    /** The Unstable SMP death sound (the Wither spawn sound), heard by the player wherever they are. */
    void deathSound(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            VexBridge.runRawQuiet(server, "playsound minecraft:entity.wither.spawn master " + player.getName().getString()
                    + " " + player.getX() + " " + player.getY() + " " + player.getZ() + " 1 1 1");
        }
    }

    /** The player hit one of this session's bots. */
    void onPlayerHitBot(MinecraftServer server, ServerPlayer player, StoryBot bot) {
        if (waiting == Waiting.HIT && (bot.requestedName.equalsIgnoreCase(hitTarget) || bot.name().equalsIgnoreCase(hitTarget))) {
            waiting = Waiting.NONE;
            objectiveDone(server, player);
        }
    }

    private void orbitalStrike(MinecraftServer server, ServerPlayer player) {
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < barrageCount; i++) {
            double x = player.getX() + random.nextInt(11) - 5;
            double z = player.getZ() + random.nextInt(11) - 5;
            double y = player.getY() + 18;
            VexBridge.runRawQuiet(server, "summon minecraft:tnt " + x + " " + y + " " + z + " {fuse:45}");
        }
        VexBridge.runRawQuiet(server, "playsound minecraft:entity.firework_rocket.launch master "
                + player.getName().getString() + " ~ ~ ~ 1 0.6");
    }

    /** "/story next": finishes the current objective. Used for beats not built yet. */
    boolean completeObjective() {
        if (waiting != Waiting.OBJECTIVE && waiting != Waiting.HIT) {
            return false;
        }
        waiting = Waiting.NONE;
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                objectiveDone(server, player);
            }
        }
        return true;
    }

    /** The player would have died: undo it and go back to the last checkpoint. */
    void onPlayerDefeated(MinecraftServer server, ServerPlayer player) {
        barrageUntil = -1;
        deathSound(server);
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
        barrageUntil = -1;
        removeAllBots(server);
        VexBridge.runRaw(server, "bossbar remove " + barId());
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

    /**
     * A position from the episode file. "x" is blocks in front of the player (where
     * they faced when the scene was set up), "z" is blocks to their right, and "y"
     * is up. Characters are then moved to the nearest safe ground at about the
     * scene's height, so they don't land on a spike or inside a wall.
     */
    private BlockPos pos(JsonObject s) {
        double yaw = Math.toRadians(anchorYaw);
        double forward = int_(s, "x", 0);
        double right = int_(s, "z", 0);
        int x = anchor.getX() + (int) Math.round(-Math.sin(yaw) * forward - Math.cos(yaw) * right);
        int z = anchor.getZ() + (int) Math.round(Math.cos(yaw) * forward - Math.sin(yaw) * right);
        int y = anchor.getY() + int_(s, "y", 0);
        Level level = level();
        if (level == null || s.has("exact")) {
            return new BlockPos(x, y, z);
        }
        return ScenePlacer.safeSpot(level, x, z, y);
    }

    private Level level() {
        if (server == null) {
            return null;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? null : player.level();
    }

    // ---- the on-screen guide: a bar at the top that always says where you are and what to do ----

    private int parts() {
        int n = 0;
        for (JsonObject st : episode.steps()) {
            if ("part".equals(str(st, "type", ""))) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    private String barId() {
        return "unstablestory:story_" + playerId.toString().substring(0, 8);
    }

    private void newObjective(MinecraftServer server, ServerPlayer player, String text) {
        objective = text;
        player.sendSystemMessage(Component.literal("> ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
        VexBridge.runRaw(server, "playsound minecraft:block.note_block.pling master " + player.getName().getString());
        updateHud(server, player);
    }

    private void objectiveDone(MinecraftServer server, ServerPlayer player) {
        if (!objective.isEmpty()) {
            player.sendSystemMessage(Component.literal("Done: " + objective).withStyle(ChatFormatting.GREEN));
            VexBridge.runRaw(server, "playsound minecraft:entity.player.levelup master " + player.getName().getString());
        }
        objective = "";
        updateHud(server, player);
    }

    /** /story status: where you are and what to do. */
    Component status() {
        String where = partTitle.isEmpty() ? episode.name() : "Part " + partNumber + " of " + parts() + ": " + partTitle;
        String what = objective.isEmpty() ? "Watch the story; the next objective will show up here." : objective;
        return Component.literal(episode.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n" + where).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\nNow: " + what).withStyle(ChatFormatting.WHITE));
    }

    private void updateHud(MinecraftServer server, ServerPlayer player) {
        String id = barId();
        String name = player.getName().getString();
        if (!hudShown) {
            VexBridge.runRaw(server, "bossbar remove " + id);
            VexBridge.runRaw(server, "bossbar add " + id + " " + text(episode.name(), "gold"));
            VexBridge.runRaw(server, "bossbar set " + id + " color yellow");
            VexBridge.runRaw(server, "bossbar set " + id + " max " + Math.max(1, episode.steps().size()));
            VexBridge.runRaw(server, "bossbar set " + id + " players " + name);
            hudShown = true;
        }
        StringBuilder line = new StringBuilder();
        line.append(partTitle.isEmpty() ? episode.name() : "Part " + partNumber + "/" + parts() + ": " + partTitle);
        if (!objective.isEmpty()) {
            line.append("  |  ").append(objective);
            if (waiting == Waiting.FIGHT) {
                line.append(" (").append(hostileBotsAliveCount()).append(" left)");
            } else if (waiting == Waiting.ESCAPE && escapeDeadline >= 0) {
                long secs = Math.max(0, (escapeDeadline - server.getTickCount()) / 20);
                line.append(" (").append(secs).append("s)");
            } else if (waiting == Waiting.OBJECTIVE || waiting == Waiting.HIT) {
                line.append("  (/story next to skip)");
            }
        }
        VexBridge.runRawQuiet(server, "bossbar set " + id + " name " + text(line.toString(), objective.isEmpty() ? "gold" : "yellow"));
        VexBridge.runRawQuiet(server, "bossbar set " + id + " value " + Math.min(step, episode.steps().size()));
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
