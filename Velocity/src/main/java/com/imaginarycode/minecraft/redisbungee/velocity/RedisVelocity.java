package com.imaginarycode.minecraft.redisbungee.velocity;

import com.ayanix.panther.impl.velocity.storage.VelocityYAMLStorage;
import com.ayanix.panther.storage.configuration.Configuration;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.*;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.velocity.util.IOUtil;
import com.imaginarycode.minecraft.redisbungee.velocity.util.LuaManager;
import com.imaginarycode.minecraft.redisbungee.velocity.util.VelocityUtil;
import com.imaginarycode.minecraft.redisbungee.velocity.util.uuid.NameFetcher;
import com.imaginarycode.minecraft.redisbungee.velocity.util.uuid.UUIDFetcher;
import com.imaginarycode.minecraft.redisbungee.velocity.util.uuid.UUIDTranslator;
import com.squareup.okhttp.Dispatcher;
import com.squareup.okhttp.OkHttpClient;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Synchronized;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
public final class RedisVelocity {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(4, new ThreadFactoryBuilder().setNameFormat("RedisBungee %d").build());

    @Getter
    private static final Gson gson = new Gson();
    private static RedisVelocityAPI api;
    @Getter(AccessLevel.PACKAGE)
    private static PubSubListener psl = null;
    @Getter
    private JedisPool pool;
    @Getter
    private UUIDTranslator uuidTranslator;
    @Getter(AccessLevel.PACKAGE)
    private static RedisVelocityConfiguration configuration;
    @Getter
    private DataManager dataManager;
    @Getter
    private static OkHttpClient httpClient;

    @Getter(value = AccessLevel.PACKAGE, onMethod_ = {@Synchronized})
    private final List<String> serverIds = new ArrayList<>();
    private final AtomicInteger nagAboutServers = new AtomicInteger();
    private final AtomicInteger globalPlayerCount = new AtomicInteger();

    private Future<?> integrityCheck;
    private Future<?> heartbeatTask;
    private ScheduledFuture<?> playerCountUpdateTask;

    private LuaManager.Script serverToPlayersScript;
    private LuaManager.Script getPlayerCountScript;

    @Getter
    private double multiplier = 1;

    //DEBUG
    long lastHeartbeat;

    private static final Object SERVER_TO_PLAYERS_KEY = new Object();
    private final Cache<Object, Multimap<String, UUID>> serverToPlayersCache = CacheBuilder.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build();

    @Getter
    private final ProxyServer proxy;
    private final Path pluginDir;
    @Getter
    private VelocityYAMLStorage config;
    @Getter
    private final Logger logger;
    private final PluginDescription pluginDescription;

    @Inject
    public RedisVelocity(ProxyServer proxy, PluginDescription pluginDescription, Logger logger, @DataDirectory Path pluginDir)
    {
        this.proxy = proxy;
        this.logger = logger;
        this.pluginDir = pluginDir;
        this.pluginDescription = pluginDescription;
    }

    /**
     * Fetch the {@link RedisVelocityAPI} object created on plugin start.
     *
     * @return the {@link RedisVelocityAPI} object
     */
    public static RedisVelocityAPI getApi() {
        return api;
    }

    static PubSubListener getPubSubListener() {
        return psl;
    }

    private List<String> getCurrentServerIds(boolean nag, boolean lagged)
    {
        try (Jedis jedis = pool.getResource())
        {
            int nagTime = 0;

            if (nag)
            {
                nagTime = nagAboutServers.decrementAndGet();

                if (nagTime <= 0)
                {
                    nagAboutServers.set(10);
                }
            }

            ImmutableList.Builder<String> servers    = ImmutableList.builder();
            Map<String, String>           heartbeats = jedis.hgetAll("heartbeats");
            final long                    time       = getRedisTime(jedis.time());

            for (Map.Entry<String, String> entry : heartbeats.entrySet())
            {
                try
                {
                    long stamp = Long.parseLong(entry.getValue());

                    if (!lagged || time >= stamp + 50) {
                        servers.add(entry.getKey());
                    }

                    if ((lagged ? time < stamp + 30 : time > stamp + 30) && nag && nagTime <= 0)
                    {
                        getLogger().warn(entry.getKey() + " is " + (time - stamp) + " seconds behind! (Time not synchronized or server down?) (Continuing anyways..)");
                    }
                } catch (NumberFormatException ignored)
                {
                }
            }

            return servers.build();
        } catch (JedisConnectionException e)
        {
            getLogger().warn("Unable to fetch server IDs", e);
            return Collections.singletonList(configuration.getServerId());
        }
    }

    public Set<UUID> getPlayersOnProxy(String server) {
        checkArgument(getServerIds().contains(server), server + " is not a valid proxy ID");

        try (Jedis jedis = pool.getResource()) {
            Set<String> users = jedis.smembers("proxy:" + server + ":usersOnline");
            ImmutableSet.Builder<UUID> builder = ImmutableSet.builder();

            for (String user : users) {
                builder.add(UUID.fromString(user));
            }

            return builder.build();
        }
    }

    Multimap<String, UUID> serversToPlayers() {
        try {
            return serverToPlayersCache.get(SERVER_TO_PLAYERS_KEY, () -> {
                Collection<String> data = (Collection<String>) serverToPlayersScript.eval(ImmutableList.<String>of(), getServerIds());

                ImmutableMultimap.Builder<String, UUID> builder = ImmutableMultimap.builder();
                String key = null;

                for (String s : data) {
                    if (key == null) {
                        key = s;
                        continue;
                    }

                    builder.put(key, UUID.fromString(s));
                    key = null;
                }

                return builder.build();
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    int getCount() {
        return globalPlayerCount.get();
    }

    int getCurrentCount() {
        Long count = (Long) getPlayerCountScript.eval(ImmutableList.of(), ImmutableList.of());
        return count.intValue();
    }

    public Set<String> getLocalPlayersAsUuidStrings() {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();

        for (Player player : getProxy().getAllPlayers()) {
            builder.add(player.getUniqueId().toString());
        }

        return builder.build();
    }

    Set<UUID> getPlayers() {
        ImmutableSet.Builder<UUID> setBuilder = ImmutableSet.builder();

        if (pool != null) {
            try (Jedis rsc = pool.getResource()) {
                List<String> keys = new ArrayList<>();

                for (String i : getServerIds()) {
                    keys.add("proxy:" + i + ":usersOnline");
                }

                if (!keys.isEmpty()) {
                    Set<String> users = rsc.sunion(keys.toArray(new String[keys.size()]));

                    if (users != null && !users.isEmpty()) {
                        for (String user : users) {
                            try {
                                setBuilder = setBuilder.add(UUID.fromString(user));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().warn("Unable to get connection from pool - did your Redis server go away?", e);
                throw new RuntimeException("Unable to get all players online", e);
            }
        }
        return setBuilder.build();
    }

    final void sendProxyCommand(@NonNull String proxyId, @NonNull String command) {
        checkArgument(getServerIds().contains(proxyId) || proxyId.equals("allservers"), "proxyId is invalid");
        sendChannelMessage("redisbungee-" + proxyId, command);
    }

    final void sendChannelMessage(String channel, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        } catch (JedisConnectionException e) {
            // Redis server has disappeared!
            getLogger().warn("Unable to get connection from pool - did your Redis server go away?", e);
            throw new RuntimeException("Unable to publish channel message", e);
        }
    }

    private long getRedisTime(List<String> timeRes) {
        return Long.parseLong(timeRes.get(0));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        onEnable();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onProxyShutdownEvent(ProxyShutdownEvent event) {
        onDisable();
    }

    public void onEnable() {
        try {
            loadConfig();
        } catch (IOException e) {
            throw new RuntimeException("Unable to load/save config", e);
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Unable to connect to your Redis server!", e);
        }
        if (pool != null) {
            try (Jedis tmpRsc = pool.getResource()) {
                Set<String>      lastSeenCache = tmpRsc.keys("player:*");
                Iterator<String> namesIterator = lastSeenCache.iterator();

                if (getConfiguration().getPurgeLastSeenAtLimit() > 0 && lastSeenCache.size() >= getConfiguration().getPurgeLastSeenAtLimit())
                {
                    System.out.println("Purging any last-seen keys past " + configuration.getPurgeAfterDays() + " days");
                    int purgeCount = 0;

                    while (namesIterator.hasNext())
                    {
                        String key = namesIterator.next();

                        //Player is currently online on a server, keep in-cache
                        if (tmpRsc.hget(key, "server") == null)
                        {
                            String result = tmpRsc.hget(key, "online");

                            if (result == null || TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - Long.parseLong(result)) > configuration.getPurgeAfterDays())
                            {
                                tmpRsc.del(key);
                                purgeCount++;
                            }
                        }
                    }

                    if (purgeCount > 0)
                    {
                        System.out.println("Successfully purged " + purgeCount + " last-seen entries");
                    }

                    System.out.println("Fetching UUID cache");
                    Map<String, String> uuidCache = tmpRsc.hgetAll("uuid-cache");

                    System.out.println("Cleaning out the uuid cache");
                    int originalSize = uuidCache.size();

                    for (Iterator<Map.Entry<String, String>> it = uuidCache.entrySet().iterator(); it.hasNext(); )
                    {
                        CachedUUIDEntry entry = gson.fromJson(it.next().getValue(), CachedUUIDEntry.class);

                        if (entry.expired())
                        {
                            it.remove();
                        }
                    }

                    int newSize = uuidCache.size();

                    System.out.println("Deleting " + (originalSize - newSize) + " uuid cache records");
                    tmpRsc.del("uuid-cache");
                    tmpRsc.hmset("uuid-cache", uuidCache);
                    System.out.println("Deletion complete.");
                }

                // This is more portable than INFO <section>
                String info = tmpRsc.info();
                for (String s : info.split("\r\n")) {
                    if (s.startsWith("redis_version:")) {
                        String version = s.split(":")[1];
                        if (!RedisUtil.canUseLua(version)) {
                            getLogger().warn("Your version of Redis (" + version + ") is not at least version 2.6. RedisBungee requires a newer version of Redis.");
                            throw new RuntimeException("Unsupported Redis version detected");
                        } else {
                            LuaManager manager = new LuaManager(this);
                            serverToPlayersScript = manager.createScript(IOUtil.readInputStreamAsString(VelocityUtil.getResourceAsStream(getClass(), "lua/server_to_players.lua")));
                            getPlayerCountScript = manager.createScript(IOUtil.readInputStreamAsString(VelocityUtil.getResourceAsStream(getClass(), "lua/get_player_count.lua")));
                        }
                        break;
                    }
                }

                tmpRsc.hset("heartbeats", configuration.getServerId(), tmpRsc.time().get(0));

                long uuidCacheSize = tmpRsc.hlen("uuid-cache");
                if (uuidCacheSize > 750000) {
                    getLogger().info("Looks like you have a really big UUID cache! Run https://www.spigotmc.org/resources/redisbungeecleaner.8505/ as soon as possible.");
                }
            }
            getServerIds().clear();
            getServerIds().addAll(getCurrentServerIds(true, false));
            uuidTranslator = new UUIDTranslator(this);
            heartbeatTask = SCHEDULER.scheduleAtFixedRate(() -> {
                try (Jedis rsc = pool.getResource()) {
                    long redisTime = getRedisTime(rsc.time());
                    rsc.hset("heartbeats", configuration.getServerId(), String.valueOf(redisTime));
                    lastHeartbeat = System.currentTimeMillis();
                } catch (JedisConnectionException e)
                {
                    // Redis server has disappeared!
                    getLogger().warn("Unable to update heartbeat - did your Redis server go away?", e);
                }
            }, 0, 1, TimeUnit.SECONDS);

            playerCountUpdateTask = SCHEDULER.scheduleWithFixedDelay(() -> {
                try
                {
                    getServerIds().clear();
                    getServerIds().addAll(getCurrentServerIds(true, false));
                    globalPlayerCount.set(getCurrentCount());
                } catch (Throwable e)
                {
                    getLogger().warn("Unable to update data - did your Redis server go away?", e);
                }
            }, 0, 3, TimeUnit.SECONDS);

            dataManager = new DataManager(this);

            if (configuration.isRegisterBungeeCommands()) {
                getProxy().getCommandManager().register("glist", new RedisVelocityCommands.GlistCommand(this), "redisbungee", "rglist");
                getProxy().getCommandManager().register("find", new RedisVelocityCommands.FindCommand(this), "rfind");
                getProxy().getCommandManager().register("lastseen", new RedisVelocityCommands.LastSeenCommand(this), "rlastseen");
                getProxy().getCommandManager().register("ip", new RedisVelocityCommands.IpCommand(this), "playerip", "rip", "rplayerip");
            }

            getProxy().getCommandManager().register("sendtoall", new RedisVelocityCommands.SendToAll(this), "rsendtoall");
            getProxy().getCommandManager().register("serverid", new RedisVelocityCommands.ServerId(this), "rserverid");
            getProxy().getCommandManager().register("serverids", new RedisVelocityCommands.ServerIds());
            getProxy().getCommandManager().register("pproxy", new RedisVelocityCommands.PlayerProxyCommand(this));
            getProxy().getCommandManager().register("plist", new RedisVelocityCommands.PlistCommand(this), "rplist");
            getProxy().getCommandManager().register("rdebug", new RedisVelocityCommands.DebugCommand(this));
            api = new RedisVelocityAPI(this);

            getProxy().getEventManager().register(this, new RedisVelocityListener(this, configuration.getExemptAddresses()));
            getProxy().getEventManager().register(this, dataManager);

            psl = new PubSubListener();
            getProxy().getScheduler().buildTask(this, psl).schedule();

            integrityCheck = SCHEDULER.scheduleAtFixedRate(this::checkIntegrity, 0, 10, TimeUnit.SECONDS);
        }

        RedisVelocityListener.IDENTIFIERS.forEach(getProxy().getChannelRegistrar()::register);
    }

    public void onDisable() {
        if (pool != null) {
            // Poison the PubSub listener
            psl.poison();
            integrityCheck.cancel(true);
            heartbeatTask.cancel(true);
            playerCountUpdateTask.cancel(true);
            getProxy().getEventManager().unregisterListener(this, this);

            try (Jedis tmpRsc = pool.getResource()) {
                tmpRsc.hdel("heartbeats", configuration.getServerId());
                if (tmpRsc.scard("proxy:" + configuration.getServerId() + ":usersOnline") > 0) {
                    Set<String> players = tmpRsc.smembers("proxy:" + configuration.getServerId() + ":usersOnline");
                    for (String member : players)
                        RedisUtil.cleanUpPlayer(member, tmpRsc);
                }
            }

            pool.destroy();
        }

        SCHEDULER.shutdown();
        try
        {
            SCHEDULER.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored)
        {
        } finally
        {
            SCHEDULER.shutdownNow();
        }
    }

    private void loadConfig() throws IOException, JedisConnectionException {
        this.config = new VelocityYAMLStorage(proxy, pluginDir, "config");

        Configuration configuration = config.getConfig();

        final String redisServer = configuration.getString("redis-server", "localhost");
        final int redisPort = configuration.getInt("redis-port", 6379);
        String redisPassword = configuration.getString("redis-password");
        String serverId = configuration.getString("server-id");

        if (redisPassword != null && (redisPassword.isEmpty() || redisPassword.equals("none"))) {
            redisPassword = null;
        }

        // Configuration sanity checks.
        if (serverId == null || serverId.isEmpty()) {
            throw new RuntimeException("server-id is not specified in the configuration or is empty");
        }

        multiplier = configuration.getDouble("multiplier", 1);

        if (redisServer != null && !redisServer.isEmpty()) {
            final String finalRedisPassword = redisPassword;
            FutureTask<JedisPool> task = new FutureTask<>(() -> {
                // Create the pool...
                JedisPoolConfig config = new JedisPoolConfig();
                config.setMaxTotal(Math.max(configuration.getInt("max-redis-connections", 8), 4));
                config.setMinIdle(config.getMaxTotal() / 2);
                return new JedisPool(config, redisServer, redisPort, 0, finalRedisPassword);
            });

            executeAsync(task);

            try {
                pool = task.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Unable to create Redis pool", e);
            }

            // Test the connection
            try (Jedis rsc = pool.getResource()) {
                rsc.ping();

                // If that worked, now we can check for an existing, alive Bungee:
                if (rsc.hexists("heartbeats", serverId)) {
                    try {
                        long value = Long.parseLong(rsc.hget("heartbeats", serverId));
                        long redisTime = getRedisTime(rsc.time());

                        if (redisTime < value + 20) {
                            getLogger().warn("You have launched a possible impostor BungeeCord instance. Another instance is already running.");
                            getLogger().warn("For data consistency reasons, please fix this if 2 instances of the same id are running");
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                FutureTask<Void> task2 = new FutureTask<>(() -> {
                    httpClient = new OkHttpClient();

                    Dispatcher dispatcher = new Dispatcher();
                    httpClient.setDispatcher(dispatcher);

                    NameFetcher.setHttpClient(httpClient);
                    UUIDFetcher.setHttpClient(httpClient);

                    RedisVelocity.configuration = new RedisVelocityConfiguration(RedisVelocity.this.getPool(), configuration);
                    return null;
                });

                executeAsync(task2);

                try {
                    task2.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("Unable to create HTTP client", e);
                }

                getLogger().warn("Successfully connected to Redis.");
            } catch (JedisConnectionException e) {
                pool.destroy();
                pool = null;
                throw e;
            }
        } else {
            throw new RuntimeException("No redis server specified!");
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class PubSubListener implements Runnable {
        private JedisPubSubHandler jpsh;

        private Set<String> addedChannels = new HashSet<String>();

        @Override
        public void run() {
            boolean broken = false;
            try (Jedis rsc = pool.getResource()) {
                try {
                    jpsh = new JedisPubSubHandler();
                    addedChannels.add("redisbungee-" + configuration.getServerId());
                    addedChannels.add("redisbungee-allservers");
                    addedChannels.add("redisbungee-data");
                    rsc.subscribe(jpsh, addedChannels.toArray(new String[0]));
                } catch (Exception e) {
                    // FIXME: Extremely ugly hack
                    // Attempt to unsubscribe this instance and try again.
                    getLogger().warn("PubSub error, attempting to recover.", e);
                    try {
                        jpsh.unsubscribe();
                    } catch (Exception e1) {
                        /* This may fail with
                        - java.net.SocketException: Broken pipe
                        - redis.clients.jedis.exceptions.JedisConnectionException: JedisPubSub was not subscribed to a Jedis instance
                        */
                    }
                    broken = true;
                }
            } catch (JedisConnectionException e) {
                getLogger().warn("PubSub error, attempting to recover in 5 secs.");
                executeAsyncLater(PubSubListener.this, TimeUnit.SECONDS, 5L);
            }

            if (broken) {
                run();
            }
        }

        public void addChannel(String... channel) {
            addedChannels.addAll(Arrays.asList(channel));
            jpsh.subscribe(channel);
        }

        public void removeChannel(String... channel) {
            addedChannels.removeAll(Arrays.asList(channel));
            jpsh.unsubscribe(channel);
        }

        public void poison() {
            addedChannels.clear();
            jpsh.unsubscribe();
        }
    }

    private class JedisPubSubHandler extends JedisPubSub {
        @Override
        public void onMessage(final String s, final String s2) {
            if (s2.trim().length() == 0) return;
            getProxy().getScheduler().buildTask(RedisVelocity.this, new Runnable() {
                @Override
                public void run() {
                    getProxy().getEventManager().fireAndForget(new PubSubMessageEvent(s, s2));
                }
            }).schedule();
        }
    }

    public void executeAsync(Runnable runnable) {
        this.getProxy().getScheduler().buildTask(this, runnable).schedule();
    }

    public void executeAsyncLater(Runnable runnable, TimeUnit timeUnit, long time) {
        this.getProxy().getScheduler().buildTask(this, runnable).delay(time, timeUnit).schedule();
    }

    private void checkIntegrity() {
        try (Jedis tmpRsc = pool.getResource()) {
            Set<String> players = getLocalPlayersAsUuidStrings();
            Set<String> playersInRedis = tmpRsc.smembers("proxy:" + configuration.getServerId() + ":usersOnline");
            List<String> lagged = getCurrentServerIds(false, true);

            // Clean up lagged players.
            for (String s : lagged) {
                Set<String> laggedPlayers = tmpRsc.smembers("proxy:" + s + ":usersOnline");
                tmpRsc.del("proxy:" + s + ":usersOnline");

                if (!laggedPlayers.isEmpty()) {
                    getLogger().info("Cleaning up lagged proxy " + s + " (" + laggedPlayers.size() + " players)...");

                    for (String laggedPlayer : laggedPlayers) {
                        RedisUtil.cleanUpPlayer(laggedPlayer, tmpRsc);
                    }
                }
            }

            Set<String> absentLocally = new HashSet<>(playersInRedis);
            absentLocally.removeAll(players);

            Set<String> absentInRedis = new HashSet<>(players);
            absentInRedis.removeAll(playersInRedis);

            for (String member : absentLocally) {
                boolean found = false;
                for (String proxyId : getServerIds()) {
                    if (proxyId.equals(configuration.getServerId())) continue;
                    if (tmpRsc.sismember("proxy:" + proxyId + ":usersOnline", member)) {
                        // Just clean up the set.
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    RedisUtil.cleanUpPlayer(member, tmpRsc);
                    getLogger().warn("Player found in set that was not found locally and globally: " + member);
                } else {
                    tmpRsc.srem("proxy:" + configuration.getServerId() + ":usersOnline", member);
                    getLogger().warn("Player found in set that was not found locally, but is on another proxy: " + member);
                }
            }

            Pipeline pipeline = tmpRsc.pipelined();

            for (String player : absentInRedis) {
                // Player not online according to Redis but not BungeeCord.
                getLogger().warn("Player " + player + " is on the proxy but not in Redis.");

                Optional<Player> proxiedPlayer = proxy.getPlayer(UUID.fromString(player));

                if (!proxiedPlayer.isPresent())
                    continue; // We'll deal with it later.

                RedisUtil.createPlayer(proxiedPlayer.get(), pipeline, true);
            }

            pipeline.sync();

            final long          time       = getRedisTime(tmpRsc.time());
            Map<String, String> heartbeats = tmpRsc.hgetAll("heartbeats");

            for (Map.Entry<String, String> entry : heartbeats.entrySet())
            {
                long stamp = Long.parseLong(entry.getValue());

                if (time - stamp > 3600)
                {
                    tmpRsc.hdel("heartbeats", entry.getKey());
                    getLogger().info("Heartbeat of " + entry.getKey() + " was removed because it has been inactive for more than 1 hour.");
                }
            }
        } catch (Throwable e) {
            getLogger().warn("Unable to run integrity checks", e);
        }
    }
}
