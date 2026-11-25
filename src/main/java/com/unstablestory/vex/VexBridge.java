package com.unstablestory.vex;

import com.unstablestory.StoryMode;
import com.unstablestory.config.StoryConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

/**
 * The only class that talks to VexBot. It fills in a command template from
 * {@link StoryConfig} and runs it as the server, so a change in VexBot's
 * commands only ever needs a change here or in the config file.
 */
public final class VexBridge {
    private VexBridge() {}

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
        String command = StoryConfig.command(key);
        if (command == null || command.isBlank()) {
            StoryMode.LOGGER.warn("No VexBot command set for '{}'", key);
            return;
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            command = command.replace("{" + e.getKey() + "}", e.getValue());
        }
        runRaw(server, command);
    }

    /** Runs any command as the server, without printing its output to chat. */
    public static void runRaw(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        StoryMode.LOGGER.debug("Running: /{}", command);
        server.getCommands().performPrefixedCommand(source, command);
    }
}
