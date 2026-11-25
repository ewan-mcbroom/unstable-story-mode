package com.unstablestory.vex;

import com.unstablestory.StoryMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls VexBot's own Java functions (VexBot is MIT-licensed; checked against
 * VexBot 3.2.0+mc1.21.11). They are looked up by name at runtime, so the mod
 * still loads without VexBot, and anything missing falls back to chat commands.
 *
 * Used: vexbot.api.VexBotApi (spawnBot, despawnBot, hunt),
 *       vexbot.bot.BotManager (setPendingKit),
 *       vexbot.kit.KitManager (load, give, exists),
 *       vexbot.team.TeamManager (getOrCreate, addBotsToTeam, addPlayerToTeam,
 *       setMode, setTargets, setTargetTeams, setTeamRevengeMode).
 */
public final class VexApi {
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static Boolean available = null;

    private VexApi() {}

    public static boolean available() {
        if (available == null) {
            try {
                Class.forName("vexbot.api.VexBotApi");
                available = true;
            } catch (ClassNotFoundException e) {
                available = false;
                StoryMode.LOGGER.warn("VexBot's API not found; using chat commands instead");
            }
        }
        return available;
    }

    private static Method method(String cls, String name, Class<?>... params) throws ReflectiveOperationException {
        String key = cls + "#" + name + "/" + params.length;
        Method m = METHODS.get(key);
        if (m == null) {
            m = Class.forName(cls).getMethod(name, params);
            METHODS.put(key, m);
        }
        return m;
    }

    private static Object call(String cls, String name, Class<?>[] params, Object... args) throws ReflectiveOperationException {
        return method(cls, name, params).invoke(null, args);
    }

    // ---- kits ----

    public static boolean kitExists(MinecraftServer server, String kit) {
        try {
            return (Boolean) call("vexbot.kit.KitManager", "exists",
                    new Class<?>[]{MinecraftServer.class, String.class}, server, kit);
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot kit check failed for {}", kit, e);
            return true;
        }
    }

    /** Give a saved kit to any player or bot. */
    public static boolean giveKit(MinecraftServer server, ServerPlayer target, String kit) {
        try {
            return (Boolean) call("vexbot.kit.KitManager", "give",
                    new Class<?>[]{MinecraftServer.class, ServerPlayer.class, String.class}, server, target, kit);
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot could not give kit {}", kit, e);
            return false;
        }
    }

    // ---- bots ----

    /** Spawn a bot. The kit is handed to VexBot first, so the bot appears wearing it. */
    public static boolean spawn(MinecraftServer server, String name, String kit, double x, double y, double z) {
        try {
            if (kit != null && !kit.isEmpty()) {
                Object stacks = call("vexbot.kit.KitManager", "load",
                        new Class<?>[]{MinecraftServer.class, String.class}, server, kit);
                if (stacks != null) {
                    call("vexbot.bot.BotManager", "setPendingKit",
                            new Class<?>[]{String.class, ItemStack[].class}, name, stacks);
                }
            }
            return (Boolean) call("vexbot.api.VexBotApi", "spawnBot",
                    new Class<?>[]{MinecraftServer.class, String.class, Vec3.class, float.class, String.class},
                    server, name, new Vec3(x + 0.5, y, z + 0.5), 0f, "minecraft:overworld");
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot could not spawn {}", name, e);
            return false;
        }
    }

    public static boolean despawn(MinecraftServer server, String name) {
        try {
            return (Boolean) call("vexbot.api.VexBotApi", "despawnBot",
                    new Class<?>[]{MinecraftServer.class, String.class}, server, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot could not remove {}", name, e);
            return false;
        }
    }

    /** Make a bot go after one player or bot. */
    public static boolean hunt(String bot, String target) {
        try {
            return (Boolean) call("vexbot.api.VexBotApi", "hunt",
                    new Class<?>[]{String.class, String.class}, bot, target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot could not set {} to hunt {}", bot, target, e);
            return false;
        }
    }

    // ---- teams ----

    /**
     * Put a bot on a team and set how that team behaves.
     * mode: NONE (attacks nobody), ALL_PLAYERS, SPECIFIC (named targets), SPECIFIC_TEAMS.
     */
    public static void team(String team, String bot, String mode, boolean revenge,
                            Collection<String> targets, Collection<String> targetTeams, Collection<String> friendlyPlayers) {
        String tm = "vexbot.team.TeamManager";
        try {
            call(tm, "getOrCreate", new Class<?>[]{String.class}, team);
            call(tm, "addBotsToTeam", new Class<?>[]{String.class, Collection.class}, team, List.of(bot));
            for (String p : friendlyPlayers) {
                call(tm, "addPlayerToTeam", new Class<?>[]{String.class, String.class}, team, p);
            }
            Class<?> modeClass = Class.forName(tm + "$TargetMode");
            Object modeValue = modeClass.getMethod("valueOf", String.class).invoke(null, mode);
            call(tm, "setMode", new Class<?>[]{String.class, modeClass}, team, modeValue);
            call(tm, "setTeamRevengeMode", new Class<?>[]{String.class, boolean.class}, team, revenge);
            if (!targets.isEmpty()) {
                call(tm, "setTargets", new Class<?>[]{String.class, Collection.class}, team, targets);
            }
            if (!targetTeams.isEmpty()) {
                call(tm, "setTargetTeams", new Class<?>[]{String.class, Collection.class}, team, targetTeams);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            StoryMode.LOGGER.warn("VexBot team setup failed for {} on {}", bot, team, e);
        }
    }
}
