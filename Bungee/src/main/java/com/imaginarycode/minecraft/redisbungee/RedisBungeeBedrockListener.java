package com.imaginarycode.minecraft.redisbungee;

import java.net.InetAddress;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent;

/**
 * @author AlexMl Created on 2024-09-06 for RedisBungee
 */
@RequiredArgsConstructor
public class RedisBungeeBedrockListener implements Listener
{

    private final RedisBungee       plugin;
    private final List<InetAddress> exemptAddresses;

    @EventHandler(priority = EventPriority.HIGH)
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
