package com.imaginarycode.minecraft.redisbungee.velocity;

import com.google.common.annotations.VisibleForTesting;
import com.velocitypowered.api.proxy.Player;
import java.net.InetAddress;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@VisibleForTesting
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RedisUtil {

    protected static void createPlayer(Player player, Pipeline pipeline, boolean fireEvent) {
        Map<String, String> playerData = new HashMap<>(4);

        InetAddress playerAddress = player.getRemoteAddress().getAddress();
        playerData.put("online", "0");
        playerData.put("ip", playerAddress.getHostAddress());
        playerData.put("proxy", RedisVelocity.getConfiguration().getServerId());

        pipeline.sadd("proxy:" + RedisVelocity.getApi().getServerId() + ":usersOnline", player.getUniqueId().toString());
        pipeline.hmset("player:" + player.getUniqueId().toString(), playerData);

        if (fireEvent) {
            pipeline.publish("redisbungee-data", RedisVelocity.getGson().toJson(new DataManager.DataManagerMessage<>(
                player.getUniqueId(), DataManager.DataManagerMessage.Action.JOIN,
                    new DataManager.LoginPayload(playerAddress))));
        }

        player.getCurrentServer().ifPresent(server -> {
            pipeline.hset("player:" + player.getUniqueId().toString(), "server", server.getServerInfo().getName());
        });
    }

    public static void cleanUpPlayer(String player, Jedis rsc) {
        rsc.srem("proxy:" + RedisVelocity.getApi().getServerId() + ":usersOnline", player);
        rsc.hdel("player:" + player, "server",  "ip", "proxy");
        long timestamp = System.currentTimeMillis();
        rsc.hset("player:" + player, "online", String.valueOf(timestamp));
        rsc.publish("redisbungee-data", RedisVelocity.getGson().toJson(new DataManager.DataManagerMessage<>(
                UUID.fromString(player), DataManager.DataManagerMessage.Action.LEAVE,
                new DataManager.LogoutPayload(timestamp))));
    }

    public static void cleanUpPlayer(String player, Pipeline rsc) {
        rsc.srem("proxy:" + RedisVelocity.getApi().getServerId() + ":usersOnline", player);
        rsc.hdel("player:" + player, "server", "ip", "proxy");
        long timestamp = System.currentTimeMillis();
        rsc.hset("player:" + player, "online", String.valueOf(timestamp));
        rsc.publish("redisbungee-data", RedisVelocity.getGson().toJson(new DataManager.DataManagerMessage<>(
                UUID.fromString(player), DataManager.DataManagerMessage.Action.LEAVE,
                new DataManager.LogoutPayload(timestamp))));
    }

    public static boolean canUseLua(String redisVersion) {
        // Need to use >=2.6 to use Lua optimizations.
        String[] args = redisVersion.split("\\.");

        if (args.length < 2) {
            return false;
        }

        int major = Integer.parseInt(args[0]);
        int minor = Integer.parseInt(args[1]);

        return major >= 3 || (major == 2 && minor >= 6);
    }
}
