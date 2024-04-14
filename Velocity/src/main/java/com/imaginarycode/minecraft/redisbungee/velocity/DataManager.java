package com.imaginarycode.minecraft.redisbungee.velocity;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PlayerChangedServerNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PlayerJoinedNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PlayerLeftNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PubSubMessageEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import redis.clients.jedis.Jedis;

import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * This class manages all the data that RedisBungee fetches from Redis, along with updates to that data.
 *
 * @since 0.3.3
 */
public class DataManager {
    private final RedisBungee plugin;
    private final Cache<UUID, String> serverCache = createCache();
    private final Cache<UUID, String> proxyCache = createCache();
    private final Cache<UUID, InetAddress> ipCache = createCache();
    private final Cache<UUID, Long> lastOnlineCache = createCache();

    public DataManager(RedisBungee plugin) {
        this.plugin = plugin;
    }

    private static <K, V> Cache<K, V> createCache() {
        // TODO: Allow customization via cache specification, ala ServerListPlus
        return CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    private final JsonParser parser = new JsonParser();

    public String getServer(final UUID uuid) {
        Optional<Player> playerOpt = plugin.getProxy().getPlayer(uuid);

        if (playerOpt.isPresent()) {
            Player player = playerOpt.get();
            Optional<ServerConnection> serverConnectionOpt = player.getCurrentServer();
            return serverConnectionOpt.map(serverConnection -> serverConnection.getServerInfo().getName()).orElse(null);
        }

        try {
            return serverCache.get(uuid, new Callable<String>() {
                @Override
                public String call() throws Exception {
                    try (Jedis tmpRsc = plugin.getPool().getResource()) {
                        return Objects.requireNonNull(tmpRsc.hget("player:" + uuid, "server"), "user not found");
                    }
                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK

            plugin.getLogger().warn("Unable to get server", e);
            throw new RuntimeException("Unable to get server for " + uuid, e);
        }
    }

    public String getProxy(final UUID uuid) {
        Optional<Player> playerOpt = plugin.getProxy().getPlayer(uuid);

        if (playerOpt.isPresent()) {
            return RedisBungee.getConfiguration().getServerId();
        }

        try {
            return proxyCache.get(uuid, new Callable<String>() {
                @Override
                public String call() throws Exception {
                    try (Jedis tmpRsc = plugin.getPool().getResource()) {
                        return Objects.requireNonNull(tmpRsc.hget("player:" + uuid, "proxy"), "user not found");
                    }
                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK
            plugin.getLogger().warn("Unable to get proxy", e);
            throw new RuntimeException("Unable to get proxy for " + uuid, e);
        }
    }

    public InetAddress getIp(final UUID uuid) {
        Optional<Player> playerOpt = plugin.getProxy().getPlayer(uuid);

        if (playerOpt.isPresent()) {
            return playerOpt.get().getRemoteAddress().getAddress();
        }

        try {
            return ipCache.get(uuid, new Callable<InetAddress>() {
                @Override
                public InetAddress call() throws Exception {
                    try (Jedis tmpRsc = plugin.getPool().getResource()) {
                        String result = tmpRsc.hget("player:" + uuid, "ip");
                        if (result == null)
                            throw new NullPointerException("user not found");
                        return InetAddresses.forString(result);
                    }
                }
            });
        } catch (ExecutionException | UncheckedExecutionException e) {
            if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
                return null; // HACK
            plugin.getLogger().warn("Unable to get IP", e);
            throw new RuntimeException("Unable to get IP for " + uuid, e);
        }
    }

    public long getLastOnline(final UUID uuid) {
        Optional<Player> playerOpt = plugin.getProxy().getPlayer(uuid);

        if (playerOpt.isPresent()) {
            return 0;
        }

        try {
            return lastOnlineCache.get(uuid, new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    try (Jedis tmpRsc = plugin.getPool().getResource()) {
                        String result = tmpRsc.hget("player:" + uuid, "online");
                        return result == null ? -1 : Long.valueOf(result);
                    }
                }
            });
        } catch (ExecutionException e) {
            plugin.getLogger().warn("Unable to get last time online", e);
            throw new RuntimeException("Unable to get last time online for " + uuid, e);
        }
    }

    private void invalidate(UUID uuid) {
        ipCache.invalidate(uuid);
        lastOnlineCache.invalidate(uuid);
        serverCache.invalidate(uuid);
        proxyCache.invalidate(uuid);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        // Invalidate all entries related to this player, since they now lie.
        invalidate(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        // Invalidate all entries related to this player, since they now lie.
        invalidate(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPubSubMessage(PubSubMessageEvent event) {
        if (!event.getChannel().equals("redisbungee-data"))
            return;

        // Partially deserialize the message so we can look at the action
        JsonObject jsonObject = parser.parse(event.getMessage()).getAsJsonObject();

        String source = jsonObject.get("source").getAsString();

        if (source.equals(RedisBungee.getConfiguration().getServerId()))
            return;

        DataManagerMessage.Action action = DataManagerMessage.Action.valueOf(jsonObject.get("action").getAsString());

        switch (action) {
            case JOIN:
                final DataManagerMessage<LoginPayload> message1 = RedisBungee.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<LoginPayload>>() {
                }.getType());
                proxyCache.put(message1.getTarget(), message1.getSource());
                lastOnlineCache.put(message1.getTarget(), (long) 0);
                ipCache.put(message1.getTarget(), message1.getPayload().getAddress());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        plugin.getProxy().getEventManager().fireAndForget(new PlayerJoinedNetworkEvent(message1.getTarget()));
                    }
                });
                break;
            case LEAVE:
                final DataManagerMessage<LogoutPayload> message2 = RedisBungee.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<LogoutPayload>>() {
                }.getType());
                invalidate(message2.getTarget());
                lastOnlineCache.put(message2.getTarget(), message2.getPayload().getTimestamp());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        plugin.getProxy().getEventManager().fireAndForget(new PlayerLeftNetworkEvent(message2.getTarget()));
                    }
                });
                break;
            case SERVER_CHANGE:
                final DataManagerMessage<ServerChangePayload> message3 = RedisBungee.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<ServerChangePayload>>() {
                }.getType());
                serverCache.put(message3.getTarget(), message3.getPayload().getServer());
                plugin.executeAsync(new Runnable() {
                    @Override
                    public void run() {
                        plugin.getProxy().getEventManager().fireAndForget(new PlayerChangedServerNetworkEvent(message3.getTarget(), message3.getPayload().getOldServer(), message3.getPayload().getServer()));
                    }
                });
                break;
        }
    }

    @Getter
    @RequiredArgsConstructor
    static class DataManagerMessage<T> {
        private final UUID target;
        private final String source = RedisBungee.getApi().getServerId();
        private final Action action; // for future use!
        private final T payload;

        enum Action {
            JOIN,
            LEAVE,
            SERVER_CHANGE
        }
    }

    @Getter
    @RequiredArgsConstructor
    static class LoginPayload {
        private final InetAddress address;
    }

    @Getter
    @RequiredArgsConstructor
    static class ServerChangePayload {
        private final String server;
        private final String oldServer;
    }

    @Getter
    @RequiredArgsConstructor
    static class LogoutPayload {
        private final long timestamp;
    }
}
