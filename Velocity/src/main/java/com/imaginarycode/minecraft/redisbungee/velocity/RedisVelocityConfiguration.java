package com.imaginarycode.minecraft.redisbungee.velocity;

import com.ayanix.panther.storage.configuration.Configuration;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import lombok.Getter;
import redis.clients.jedis.JedisPool;

import java.net.InetAddress;
import java.util.List;

public class RedisVelocityConfiguration {
    @Getter
    private final JedisPool pool;
    @Getter
    private final String serverId;
    @Getter
    private final int purgeLastSeenAtLimit;
    @Getter
    private final int purgeAfterDays;
    @Getter
    private final boolean registerBungeeCommands;
    @Getter
    private final List<InetAddress> exemptAddresses;

    public RedisVelocityConfiguration(JedisPool pool, Configuration configuration) {
        this.pool = pool;
        this.serverId = configuration.getString("server-id");
        this.registerBungeeCommands = configuration.getBoolean("register-bungee-commands", true);
        this.purgeLastSeenAtLimit   = configuration.getInt("purge-last-seen-user-limit", 30000);
        this.purgeAfterDays         = configuration.getInt("purge-after-x-days", 7);

        List<String> stringified = configuration.getStringList("exempt-ip-addresses");
        ImmutableList.Builder<InetAddress> addressBuilder = ImmutableList.builder();

        for (String s : stringified) {
            addressBuilder.add(InetAddresses.forString(s));
        }

        this.exemptAddresses = addressBuilder.build();
    }
}
