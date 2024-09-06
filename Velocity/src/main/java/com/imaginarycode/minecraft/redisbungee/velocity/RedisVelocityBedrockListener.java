package com.imaginarycode.minecraft.redisbungee.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import java.net.InetAddress;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent;

/**
 * @author AlexMl Created on 2024-09-06 for RedisBungee
 */
@RequiredArgsConstructor
public class RedisVelocityBedrockListener
{

    private final RedisVelocity     plugin;
    private final List<InetAddress> exemptAddresses;

    @Subscribe(order = PostOrder.LATE)
    public void onBedrockPing(GeyserBedrockPingEvent event)
    {
        if (exemptAddresses.contains(event.address().getAddress()))
        {
            return;
        }

        event.playerCount((int) (plugin.getCount() * plugin.getMultiplier()));
        event.maxPlayerCount(Math.max(event.playerCount(), event.maxPlayerCount()));
    }
}
