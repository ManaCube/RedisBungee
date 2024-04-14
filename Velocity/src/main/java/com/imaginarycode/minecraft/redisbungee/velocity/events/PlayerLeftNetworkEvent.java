package com.imaginarycode.minecraft.redisbungee.velocity.events;

import lombok.ToString;

import java.util.UUID;

/**
 * This event is sent when a player disconnects. RedisBungee sends the event only when
 * the proxy the player has been connected to is different than the local proxy.
 * <p>
 * This event corresponds to {@link net.md_5.bungee.api.event.PlayerDisconnectEvent}, and is fired
 * asynchronously.
 *
 * @since 0.3.4
 */
@ToString
public class PlayerLeftNetworkEvent {
    private final UUID uuid;

    public PlayerLeftNetworkEvent(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
