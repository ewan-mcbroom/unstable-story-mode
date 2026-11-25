package com.unstablestory.vex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unstablestory.StoryMode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Copies the story's kits into the vexbot_kits folder in the Minecraft folder,
 * which is where VexBot reads kit files from. A kit that is missing or out of
 * date is written; anything else in that folder is left alone.
 */
public final class KitInstaller {
    private static final String BUILT_IN = "/data/unstablestory/kits/";

    private KitInstaller() {}

    public static void install() {
        Path folder = FabricLoader.getInstance().getGameDir().resolve("vexbot_kits");
        int written = 0;
        try (InputStream in = KitInstaller.class.getResourceAsStream(BUILT_IN + "kits_index.json")) {
            if (in == null) {
                StoryMode.LOGGER.error("The mod's kit list is missing");
                return;
            }
            JsonObject kits = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("kits");
            Files.createDirectories(folder);
            for (String id : kits.keySet()) {
                if (copy(id, folder.resolve(id + ".json"))) {
                    written++;
                }
            }
            StoryMode.LOGGER.info("Story kits: {} in {}, {} added or updated", kits.size(), folder, written);
        } catch (IOException | RuntimeException e) {
            StoryMode.LOGGER.error("Could not install the story kits into {}", folder, e);
        }
    }

    private static boolean copy(String id, Path target) throws IOException {
        try (InputStream kit = KitInstaller.class.getResourceAsStream(BUILT_IN + id + ".json")) {
            if (kit == null) {
                StoryMode.LOGGER.error("Kit {} is listed but missing from the mod", id);
                return false;
            }
            byte[] bytes = kit.readAllBytes();
            if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
                return false;
            }
            Files.write(target, bytes);
            return true;
        }
    }
}
