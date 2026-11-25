package com.unstablestory.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unstablestory.StoryMode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The VexBot command templates, kept in config/unstablestory/vexbot_commands.json.
 * The VexBot syntax is not documented, so every command can be fixed in that file
 * without rebuilding the mod. Placeholders look like {name}.
 */
public final class StoryConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Raise this when the defaults change, so old command files get replaced. */
    private static final int VERSION = 4;

    public static final Map<String, String> DEFAULT_COMMANDS = new LinkedHashMap<>();
    static {
        DEFAULT_COMMANDS.put("_version", Integer.toString(VERSION));
        // From the user's VexBot guide: "/vexbot spawn Steve 10 64 -20". The kit, skin and
        // patrol are given afterwards, once the mod has found the bot's real name.
        // Teams are left out until we know VexBot's team syntax.
        DEFAULT_COMMANDS.put("spawn_on_team", "vexbot spawn {name} {x} {y} {z}");
        DEFAULT_COMMANDS.put("set_skin", "vexbot nick {name} {name} {skin}");
        DEFAULT_COMMANDS.put("give_kit", "vexbot givekit {target} {kit}");
        DEFAULT_COMMANDS.put("wander", "vexbot wander {name} {radius} {x} {y} {z}");
        DEFAULT_COMMANDS.put("remove_bot", "vexbot kill {name}");
    }

    private static final Map<String, String> commands = new LinkedHashMap<>(DEFAULT_COMMANDS);

    private StoryConfig() {}

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("unstablestory");
    }

    public static String command(String key) {
        return commands.getOrDefault(key, DEFAULT_COMMANDS.get(key));
    }

    private static int fileVersion(Path file) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            return obj.has("_version") ? Integer.parseInt(obj.get("_version").getAsString()) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Reads the command file, writing the defaults first if it does not exist yet. */
    public static void load() {
        Path file = dir().resolve("vexbot_commands.json");
        try {
            Files.createDirectories(dir());
            Files.createDirectories(dir().resolve("episodes"));
            if (Files.exists(file) && fileVersion(file) < VERSION) {
                Files.move(file, dir().resolve("vexbot_commands.old.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                StoryMode.LOGGER.info("Replaced an old {} (kept as vexbot_commands.old.json)", file.getFileName());
            }
            if (!Files.exists(file)) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(DEFAULT_COMMANDS, w);
                }
            }
            commands.clear();
            commands.putAll(DEFAULT_COMMANDS);
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    commands.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            StoryMode.LOGGER.error("Could not read {}, using the default VexBot commands", file, e);
        }
    }
}
