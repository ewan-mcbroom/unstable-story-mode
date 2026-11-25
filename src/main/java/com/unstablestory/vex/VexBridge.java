package com.unstablestory.vex;

import com.unstablestory.StoryMode;
import com.unstablestory.config.StoryConfig;
import net.minecraft.ChatFormatting;
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
        run(server, "spawn_on_team", Map.of(
                "team", team, "name", name, "kit", kit,
                "x", Integer.toString(x), "y", Integer.toString(y), "z", Integer.toString(z)));
    }

    public static void setSkin(MinecraftServer server, String name, String skin) {
        run(server, "set_skin", Map.of("name", name, "skin", skin));
    }

    public static void giveKit(MinecraftServer server, String target, String kit) {
        run(server, "give_kit", Map.of("target", target, "kit", kit));
    }

    public static void wander(MinecraftServer server, String name, int radius, int x, int y, int z) {
        run(server, "wander", Map.of("name", name, "radius", Integer.toString(radius),
                "x", Integer.toString(x), "y", Integer.toString(y), "z", Integer.toString(z)));
    }

    public static void remove(MinecraftServer server, String name) {
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

    /** Runs any command. Quietly as the server, or out loud as the debugging player. */
    public static void runRaw(MinecraftServer server, String command) {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        StoryMode.LOGGER.info("[story] /{}", command);
        ServerPlayer watcher = debugPlayer == null ? null : server.getPlayerList().getPlayer(debugPlayer);
        CommandSourceStack source;
        if (watcher != null) {
            watcher.sendSystemMessage(Component.literal("[debug] /" + command).withStyle(ChatFormatting.DARK_GRAY));
            source = watcher.createCommandSourceStack();
        } else {
            source = server.createCommandSourceStack().withSuppressedOutput();
        }
        server.getCommands().performPrefixedCommand(source, command);
    }
}
