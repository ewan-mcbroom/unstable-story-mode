package com.unstablestory.story;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A VexBot NPC that an episode spawned. VexBot may change the name we ask for
 * (SargeLAW came back as Sarge_LAW), so a bot is matched to the real player
 * entity after it appears, and from then on it is tracked by that entity's UUID
 * and real name.
 */
final class StoryBot {
    final String requestedName;
    final String skin;
    final String kit;
    final boolean hostile;
    final boolean ally;
    final int wander;
    final int x, y, z;
    final Set<UUID> playersBefore;

    UUID uuid;
    String actualName;
    boolean alive = true;
    int ticksWaiting = 0;

    StoryBot(String requestedName, String skin, String kit, boolean hostile, boolean ally, int wander, int x, int y, int z,
             Set<UUID> playersBefore) {
        this.ally = ally;
        this.requestedName = requestedName;
        this.skin = skin;
        this.kit = kit;
        this.hostile = hostile;
        this.wander = wander;
        this.x = x;
        this.y = y;
        this.z = z;
        this.playersBefore = playersBefore;
    }

    boolean found() {
        return uuid != null;
    }

    /** The name to use in commands: the real one once known. */
    String name() {
        return actualName != null ? actualName : requestedName;
    }

    /** Is this new player entity the bot we asked for? */
    boolean matches(ServerPlayer candidate) {
        if (playersBefore.contains(candidate.getUUID())) {
            return false;
        }
        return normalize(candidate.getName().getString()).equals(normalize(requestedName));
    }

    boolean isEntity(UUID id) {
        return id.equals(uuid);
    }

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static Set<UUID> currentPlayers(MinecraftServer server) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ids.add(p.getUUID());
        }
        return ids;
    }
}
