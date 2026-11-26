package com.unstablestory.vex;

import com.unstablestory.StoryMode;
import com.unstablestory.config.StoryConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * The only class that talks to VexBot. It fills in a command template from
 * {@link StoryConfig} and runs it, so a change in VexBot's commands only ever
 * needs a change here or in the config file.
 *
 * A template can hold several commands separated by ";;".
 *
 * With /story debug on, commands run as the player who turned debug on, so every
 * command and every error message shows in their chat.
 */
public final class VexBridge {
    private static UUID debugPlayer = null;

    private VexBridge() {}

    public static void setDebug(UUID player) { debugPlayer = player; }
    public static boolean debugging(UUID player) { return player.equals(debugPlayer); }

    public static void spawnOnTeam(MinecraftServer server, String team, String name, String kit, int x, int y, int z) {
        if (VexApi.available()) {
            if (!kit.isEmpty() && !VexApi.kitExists(server, kit)) {
                debug(server, "VexBot has no kit called " + kit + " (is it in vexbot_kits?)");
            }
            boolean ok = VexApi.spawn(server, name, kit, x, y, z);
            debug(server, "VexBot API: spawn " + name + " at " + x + " " + y + " " + z + " with kit " + kit + (ok ? "" : " FAILED (see log)"));
            return;
        }
        run(server, "spawn_on_team", Map.of(
                "team", team, "name", name, "kit", kit,
                "x", Integer.toString(x), "y", Integer.toString(y), "z", Integer.toString(z)));
    }

    public static void setSkin(MinecraftServer server, String name, String skin) {
        run(server, "set_skin", Map.of("name", name, "skin", skin));
    }

    public static void giveKit(MinecraftServer server, String target, String kit) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(target);
        if (VexApi.available() && player != null) {
            boolean ok = VexApi.giveKit(server, player, kit);
            debug(server, "VexBot API: give kit " + kit + " to " + target + (ok ? "" : " FAILED (does the kit exist?)"));
            return;
        }
        run(server, "give_kit", Map.of("target", target, "kit", kit));
    }

    public static void wander(MinecraftServer server, String name, int radius, int x, int y, int z) {
        run(server, "wander", Map.of("name", name, "radius", Integer.toString(radius),
                "x", Integer.toString(x), "y", Integer.toString(y), "z", Integer.toString(z)));
    }

    public static void remove(MinecraftServer server, String name) {
        if (VexApi.available()) {
            boolean ok = VexApi.despawn(server, name);
            debug(server, "VexBot API: remove " + name + (ok ? "" : " (not found)"));
            if (ok) {
                return;
            }
        }
        run(server, "remove_bot", Map.of("name", name));
    }

    private static void run(MinecraftServer server, String key, Map<String, String> values) {
        String template = StoryConfig.command(key);
        if (template == null || template.isBlank()) {
            StoryMode.LOGGER.warn("No VexBot command set for '{}'", key);
            return;
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            template = template.replace("{" + e.getKey() + "}", e.getValue());
        }
        for (String command : template.split(";;")) {
            if (!command.isBlank()) {
                runRaw(server, command.strip());
            }
        }
    }

    /** A line for the debugging player, if debug is on. */
    public static void debug(MinecraftServer server, String message) {
        StoryMode.LOGGER.info("[story] {}", message);
        ServerPlayer watcher = debugPlayer == null ? null : server.getPlayerList().getPlayer(debugPlayer);
        if (watcher != null) {
            watcher.sendSystemMessage(Component.literal("[debug] " + message).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * Runs any command as the server, so it works whatever the player's permissions
     * are (even with cheats off). With debug on, the command and anything it prints,
     * including errors, are copied to the debugging player's chat.
     */
    public static void runRaw(MinecraftServer server, String command) {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        StoryMode.LOGGER.info("[story] /{}", command);
        ServerPlayer watcher = debugPlayer == null ? null : server.getPlayerList().getPlayer(debugPlayer);
        CommandSourceStack source = server.createCommandSourceStack();
        if (watcher != null) {
            watcher.sendSystemMessage(Component.literal("[debug] /" + command).withStyle(ChatFormatting.DARK_GRAY));
            source = source.withSource(new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    watcher.sendSystemMessage(Component.literal("[debug]   ").withStyle(ChatFormatting.DARK_GRAY).append(message));
                }

                @Override
                public boolean acceptsSuccess() { return true; }

                @Override
                public boolean acceptsFailure() { return true; }

                @Override
                public boolean shouldInformAdmins() { return false; }
            });
        } else {
            source = source.withSuppressedOutput();
        }
        server.getCommands().performPrefixedCommand(source, command);
    }
}
