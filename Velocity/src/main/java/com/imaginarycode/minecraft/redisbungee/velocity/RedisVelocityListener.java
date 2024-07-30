package com.imaginarycode.minecraft.redisbungee.velocity;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.velocity.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.velocity.util.RedisCallable;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerPing;
import lombok.AllArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.net.InetAddress;
import java.util.*;

@AllArgsConstructor
public class RedisVelocityListener {

    private static final Component ALREADY_LOGGED_IN = Component.text()
                .append(Component.text("You are already logged on to this server.").color(NamedTextColor.RED))
                .append(Component.text("\n\nIt may help to try logging in again in a few minutes.\nIf this does not resolve your issue, please contact staff.")).color(NamedTextColor.GRAY)
                .build();

    private static final Component ONLINE_MODE_RECONNECT = Component.text()
                    .append(Component.text("Whoops! You need to reconnect.")).color(NamedTextColor.RED)
                    .append(Component.text("\n\nWe found someone online using your username. They were kicked and you may reconnect.\nIf this does not work, please contact staff.")).color(NamedTextColor.GRAY)
                    .build();

    public static final List<ChannelIdentifier> IDENTIFIERS = Lists.newArrayList(
        MinecraftChannelIdentifier.create("legacy", "redisbungee"),
        new LegacyChannelIdentifier("RedisBungee"),
        // This is needed for clients before 1.13
        new LegacyChannelIdentifier("legacy:redisbungee")
    );

    private final RedisVelocity plugin;
    private final List<InetAddress> exemptAddresses;

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(LoginEvent event, Continuation continuation) {
        plugin.executeAsync(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                try {
                    if (!event.getResult().isAllowed()) {
                        return null;
                    }

                    // We make sure they aren't trying to use an existing player's name.
                    // This is problematic for online-mode servers as they always disconnect old clients.
                    if (plugin.getProxy().getConfiguration().isOnlineMode()) {
                        Optional<Player> playerOpt = plugin.getProxy().getPlayer(event.getPlayer().getUsername());

                        if (playerOpt.isPresent()) {
                            event.setResult(ResultedEvent.ComponentResult.denied(ONLINE_MODE_RECONNECT));
                            return null;
                        }
                    }

                    for (String s : plugin.getServerIds()) {
                        if (jedis.sismember("proxy:" + s + ":usersOnline", event.getPlayer().getUniqueId().toString())) {
                            event.setResult(ResultedEvent.ComponentResult.denied(ALREADY_LOGGED_IN));
                            return null;
                        }
                    }

                    Pipeline pipeline = jedis.pipelined();
                    plugin.getUuidTranslator().persistInfo(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), pipeline);
                    RedisUtil.createPlayer(event.getPlayer(), pipeline, false);
                    // We're not publishing, the API says we only publish at PostLoginEvent time.
                    pipeline.sync();

                    return null;
                } finally {
                    continuation.resume();
                }
            }
        });
    }

    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        plugin.executeAsync(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                jedis.publish("redisbungee-data", RedisVelocity.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.JOIN,
                        new DataManager.LoginPayload(event.getPlayer().getRemoteAddress().getAddress()))));
                return null;
            }
        });
    }

    @Subscribe
    public void onPlayerDisconnect(final DisconnectEvent event) {
        plugin.executeAsync(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                Pipeline pipeline = jedis.pipelined();
                RedisUtil.cleanUpPlayer(event.getPlayer().getUniqueId().toString(), pipeline);
                pipeline.sync();
                return null;
            }
        });
    }

    @Subscribe
    public void onServerChange(final ServerConnectedEvent event) {
        final String currentServer = event.getServer().getServerInfo().getName();

        plugin.executeAsync(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                jedis.hset("player:" + event.getPlayer().getUniqueId().toString(), "server", currentServer);
                jedis.publish("redisbungee-data", RedisVelocity.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.SERVER_CHANGE,
                        new DataManager.ServerChangePayload(currentServer, currentServer))));
                return null;
            }
        });
    }

    @Subscribe(order = PostOrder.LATE)
    public void onPing(final ProxyPingEvent event) {
        if (exemptAddresses.contains(event.getConnection().getRemoteAddress().getAddress())) {
            return;
        }

        ServerPing.Builder ping = event.getPing().asBuilder();
        ping.onlinePlayers((int) (plugin.getCount() * plugin.getMultiplier()));
        event.setPing(ping.build());
    }

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection) || !IDENTIFIERS.contains(event.getIdentifier())) {
            return;
        }

        event.setResult(ForwardResult.handled());

        final byte[] data = Arrays.copyOf(event.getData(), event.getData().length);
        plugin.executeAsync(new Runnable() {
            @Override
            public void run() {
                ByteArrayDataInput in = ByteStreams.newDataInput(data);

                String subchannel = in.readUTF();
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                String type;

                switch (subchannel) {
                    case "PlayerList":
                        out.writeUTF("PlayerList");
                        Set<UUID> original = Collections.emptySet();
                        type = in.readUTF();
                        if (type.equals("ALL")) {
                            out.writeUTF("ALL");
                            original = plugin.getPlayers();
                        } else {
                            try {
                                original = RedisVelocity.getApi().getPlayersOnServer(type);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }

                        Set<String> players = new HashSet<>();
                        for (UUID uuid : original)
                            players.add(plugin.getUuidTranslator().getNameFromUuid(uuid, false));
                        out.writeUTF(Joiner.on(',').join(players));
                        break;

                    case "PlayerCount":
                        out.writeUTF("PlayerCount");
                        type = in.readUTF();

                        if (type.equals("ALL")) {
                            out.writeUTF("ALL");
                            out.writeInt(plugin.getCount());
                        } else {
                            out.writeUTF(type);

                            try {
                                out.writeInt(RedisVelocity.getApi().getPlayersOnServer(type).size());
                            } catch (IllegalArgumentException e) {
                                out.writeInt(0);
                            }
                        }
                        break;

                    case "LastOnline":
                        String user = in.readUTF();
                        out.writeUTF("LastOnline");
                        out.writeUTF(user);
                        out.writeLong(RedisVelocity.getApi().getLastOnline(plugin.getUuidTranslator().getTranslatedUuid(user, true)));
                        break;
                    case "ServerPlayers":
                        String type1 = in.readUTF();
                        out.writeUTF("ServerPlayers");
                        Multimap<String, UUID> multimap = RedisVelocity.getApi().getServerToPlayers();

                        boolean includesUsers;

                        switch (type1) {
                            case "COUNT":
                                includesUsers = false;
                                break;
                            case "PLAYERS":
                                includesUsers = true;
                                break;
                                default:
                                // TODO: Should I raise an error?
                                return;
                        }

                        out.writeUTF(type1);

                        if (includesUsers) {
                            Multimap<String, String> human = HashMultimap.create();

                            for (Map.Entry<String, UUID> entry : multimap.entries()) {
                                human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                            }

                            serializeMultimap(human, true, out);
                        } else {
                            serializeMultiset(multimap.keys(), out);
                        }
                        break;
                    case "Proxy":
                        out.writeUTF("Proxy");
                        out.writeUTF(RedisVelocity.getConfiguration().getServerId());
                        break;
                    case "PlayerProxy":
                        String username = in.readUTF();
                        out.writeUTF("PlayerProxy");
                        out.writeUTF(username);
                        out.writeUTF(RedisVelocity.getApi().getProxy(plugin.getUuidTranslator().getTranslatedUuid(username, true)));
                        break;
                    default:
                        return;
                }

                ((ServerConnection) event.getSource()).sendPluginMessage(event.getIdentifier(), out.toByteArray());
            }
        });
    }

    private void serializeMultiset(Multiset<String> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.elementSet().size());
        for (Multiset.Entry<String> entry : collection.entrySet()) {
            output.writeUTF(entry.getElement());
            output.writeInt(entry.getCount());
        }
    }

    private void serializeMultimap(Multimap<String, String> collection, boolean includeNames, ByteArrayDataOutput output) {
        output.writeInt(collection.keySet().size());
        for (Map.Entry<String, Collection<String>> entry : collection.asMap().entrySet()) {
            output.writeUTF(entry.getKey());
            if (includeNames) {
                serializeCollection(entry.getValue(), output);
            } else {
                output.writeInt(entry.getValue().size());
            }
        }
    }

    private void serializeCollection(Collection<?> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Object o : collection) {
            output.writeUTF(o.toString());
        }
    }

    @Subscribe
    public void onPubSubMessage(PubSubMessageEvent event) {
        if (event.getChannel().equals("redisbungee-allservers") || event.getChannel().equals("redisbungee-" + RedisVelocity.getApi().getServerId())) {
            String message = event.getMessage();
            if (message.startsWith("/"))
                message = message.substring(1);
            plugin.getLogger().info("Invoking command via PubSub: /" + message);
            plugin.getProxy().getCommandManager().executeAsync(RedisVelocityCommandSender.getSingleton(), message);
        }
    }
}
