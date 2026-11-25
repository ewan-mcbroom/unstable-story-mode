package com.unstablestory.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unstablestory.StoryMode;
import com.unstablestory.config.StoryConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One playable episode (one video), loaded from JSON. Episodes ship inside the mod
 * under data/unstablestory/episodes/, and a file with the same id in
 * config/unstablestory/episodes/ replaces the built-in one, so beats can be fixed
 * without rebuilding.
 */
public record Episode(String id, String name, String protagonist, List<JsonObject> steps) {

    private static final String BUILT_IN = "/data/unstablestory/episodes/";
    private static final Map<String, Episode> EPISODES = new LinkedHashMap<>();

    public static Map<String, Episode> all() {
        return Collections.unmodifiableMap(EPISODES);
    }

    public static Episode get(String id) {
        return EPISODES.get(id);
    }

    public static void loadAll() {
        EPISODES.clear();
        try (InputStream in = Episode.class.getResourceAsStream(BUILT_IN + "index.json")) {
            if (in != null) {
                JsonArray ids = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement id : ids) {
                    loadBuiltIn(id.getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            StoryMode.LOGGER.error("Could not read the built-in episode list", e);
        }
        Path overrides = StoryConfig.dir().resolve("episodes");
        if (Files.isDirectory(overrides)) {
            try (Stream<Path> files = Files.list(overrides)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(Episode::loadFile);
            } catch (IOException e) {
                StoryMode.LOGGER.error("Could not list {}", overrides, e);
            }
        }
        StoryMode.LOGGER.info("Loaded {} episode(s): {}", EPISODES.size(), EPISODES.keySet());
    }

    private static void loadBuiltIn(String id) {
        try (InputStream in = Episode.class.getResourceAsStream(BUILT_IN + id + ".json")) {
            if (in == null) {
                StoryMode.LOGGER.error("Episode {} is listed but its file is missing", id);
                return;
            }
            add(new InputStreamReader(in, StandardCharsets.UTF_8), id);
        } catch (IOException | RuntimeException e) {
            StoryMode.LOGGER.error("Could not load episode {}", id, e);
        }
    }

    private static void loadFile(Path file) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            add(r, file.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            StoryMode.LOGGER.error("Could not load episode file {}", file, e);
        }
    }

    private static void add(Reader reader, String source) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        String id = root.get("id").getAsString();
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement step : root.getAsJsonArray("steps")) {
            steps.add(step.getAsJsonObject());
        }
        String name = root.has("name") ? root.get("name").getAsString() : id;
        String protagonist = root.has("protagonist") ? root.get("protagonist").getAsString() : "";
        EPISODES.put(id, new Episode(id, name, protagonist, List.copyOf(steps)));
        StoryMode.LOGGER.debug("Loaded episode {} from {}", id, source);
    }
}
