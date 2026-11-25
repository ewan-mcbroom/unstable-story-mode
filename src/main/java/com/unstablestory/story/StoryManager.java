package com.unstablestory.story;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Keeps track of who is playing which episode, and feeds them ticks and deaths. */
public final class StoryManager {
    private static final Map<UUID, EpisodeSession> SESSIONS = new HashMap<>();

    private StoryManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StoryManager::tick);

        // A player in an episode never really dies: they go back to the last checkpoint.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player) {
                EpisodeSession session = SESSIONS.get(player.getUUID());
                if (session != null && !session.finished()) {
                    session.onPlayerDefeated(player.level().getServer(), player);
                    return false;
                }
            }
            return true;
        });

        // A bot we spawned has died: tell its session, so fights can end.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> onDeath(entity));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> stop(server, handler.player));
    }

    private static void onDeath(LivingEntity entity) {
        String name = entity.getName().getString();
        for (EpisodeSession session : SESSIONS.values()) {
            if (session.ownsBot(name)) {
                session.onBotDied(name);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        Iterator<EpisodeSession> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            EpisodeSession session = it.next();
            session.tick(server);
            if (session.finished()) {
                it.remove();
            }
        }
    }

    public static EpisodeSession get(ServerPlayer player) {
        return SESSIONS.get(player.getUUID());
    }

    public static void start(MinecraftServer server, ServerPlayer player, Episode episode) {
        stop(server, player);
        SESSIONS.put(player.getUUID(), new EpisodeSession(player, episode));
    }

    public static boolean stop(MinecraftServer server, ServerPlayer player) {
        EpisodeSession session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return false;
        }
        session.finish(server, player, false);
        return true;
    }
}
