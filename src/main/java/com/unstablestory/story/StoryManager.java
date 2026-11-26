package com.unstablestory.story;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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

        // Peaceful story bots (guards on patrol) can't be hurt, so they never turn on
        // the player, and they can't hurt the player either.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            StoryBot target = findBot(entity.getUUID());
            Entity attacker = source.getEntity();
            if (target != null && attacker instanceof ServerPlayer hitter) {
                EpisodeSession session = SESSIONS.get(hitter.getUUID());
                if (session != null) {
                    session.onPlayerHitBot(hitter.level().getServer(), hitter, target);
                }
            }
            if (target != null && target.peaceful()) {
                return false;
            }
            if (target != null && target.ally && attacker instanceof ServerPlayer && findBot(attacker.getUUID()) == null) {
                return false;
            }
            if (attacker != null && entity instanceof ServerPlayer) {
                StoryBot bot = findBot(attacker.getUUID());
                if (bot != null && !bot.hostile) {
                    return false;
                }
            }
            return true;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> stop(server, handler.player));
    }

    private static void onDeath(LivingEntity entity) {
        for (EpisodeSession session : SESSIONS.values()) {
            StoryBot bot = session.findBot(entity.getUUID());
            if (bot != null) {
                session.onBotDied(bot);
                session.deathSound(entity.level().getServer());
            }
        }
    }

    static StoryBot findBot(UUID entity) {
        for (EpisodeSession session : SESSIONS.values()) {
            StoryBot bot = session.findBot(entity);
            if (bot != null) {
                return bot;
            }
        }
        return null;
    }

    static boolean isTrackedBot(UUID entity) {
        return findBot(entity) != null;
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
