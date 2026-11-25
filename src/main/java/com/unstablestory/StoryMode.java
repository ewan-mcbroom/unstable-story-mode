package com.unstablestory;

import com.unstablestory.config.StoryConfig;
import com.unstablestory.story.Episode;
import com.unstablestory.story.StoryCommands;
import com.unstablestory.story.StoryManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StoryMode implements ModInitializer {
    public static final String MOD_ID = "unstablestory";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        StoryConfig.load();
        Episode.loadAll();
        StoryManager.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                StoryCommands.register(dispatcher));
        LOGGER.info("Unstable SMP Story Mode loaded");
    }
}
