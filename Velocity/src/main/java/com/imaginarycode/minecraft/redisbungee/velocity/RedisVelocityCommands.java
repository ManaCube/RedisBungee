package com.imaginarycode.minecraft.redisbungee.velocity;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * This class contains subclasses that are used for the commands RedisBungee overrides or includes: /glist, /find and /lastseen.
 * <p>
 * All classes use the {@link RedisVelocityAPI}.
 *
 * @author tuxed
 * @since 0.2.3
 */
class RedisVelocityCommands {
    private static final Component NO_PLAYER_SPECIFIED =
            Component.text("You must specify a player name.", NamedTextColor.RED);
    private static final Component PLAYER_NOT_FOUND =
            Component.text("No such player found.", NamedTextColor.RED);
    private static final Component NO_COMMAND_SPECIFIED =
            Component.text("You must specify a command to be run.", NamedTextColor.RED);

    private static String playerPlural(int num) {
        return num == 1 ? num + " player is" : num + " players are";
    }

    public static class GlistCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        GlistCommand(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                CommandSource sender = invocation.source();
                int count = RedisVelocity.getApi().getPlayerCount();
                Component playersOnline = Component.text(playerPlural(count) + " currently online.", NamedTextColor.YELLOW);

                if (invocation.arguments().length > 0 && invocation.arguments()[0].equals("showall")) {
                    Multimap<String, UUID> serverToPlayers = RedisVelocity.getApi().getServerToPlayers();
                    Multimap<String, String> human = HashMultimap.create();
                    for (Map.Entry<String, UUID> entry : serverToPlayers.entries()) {
                        human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                    }
                    for (String server : new TreeSet<>(serverToPlayers.keySet())) {
                        Component serverName = Component.text("[" + server + "] ", NamedTextColor.GREEN);
                        Component serverCount = Component.text("(" + serverToPlayers.get(server).size() + "): ", NamedTextColor.YELLOW);
                        Component serverPlayers = Component.text(Joiner.on(", ").join(human.get(server)), NamedTextColor.WHITE);
                        sender.sendMessage(Component.textOfChildren(serverName, serverCount, serverPlayers));
                    }
                    sender.sendMessage(playersOnline);
                } else {
                    sender.sendMessage(playersOnline);
                    sender.sendMessage(Component.text("To see all players online, use /glist showall.", NamedTextColor.YELLOW));
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("bungeecord.command.list");
        }
    }

    public static class FindCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        FindCommand(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                String[] args = invocation.arguments();
                CommandSource sender = invocation.source();

                if (args.length > 0) {
                    UUID uuid = plugin.getUuidTranslator().getTranslatedUuid(args[0], true);
                    if (uuid == null) {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                        return;
                    }

                    ServerInfo si = RedisVelocity.getApi().getServerFor(uuid);

                    if (si != null) {
                        Component message = Component.text(args[0] + " is on " + si.getName() + ".", NamedTextColor.BLUE);
                        sender.sendMessage(message);
                    } else {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                    }
                } else {
                    sender.sendMessage(NO_PLAYER_SPECIFIED);
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("bungeecord.command.find");
        }
    }

    public static class LastSeenCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        LastSeenCommand(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                String[] args = invocation.arguments();
                CommandSource sender = invocation.source();
                if (args.length > 0) {
                    UUID uuid = plugin.getUuidTranslator().getTranslatedUuid(args[0], true);

                    if (uuid == null) {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                        return;
                    }

                    long secs = RedisVelocity.getApi().getLastOnline(uuid);
                    TextComponent.Builder message = Component.text();
                    if (secs == 0) {
                        message.color(NamedTextColor.GREEN);
                        message.content(args[0] + " is currently online.");
                    } else if (secs != -1) {
                        message.color(NamedTextColor.BLUE);
                        message.content(args[0] + " was last online on " + new SimpleDateFormat().format(secs) + ".");
                    } else {
                        message.color(NamedTextColor.RED);
                        message.content(args[0] + " has never been online.");
                    }
                    sender.sendMessage(message.build());
                } else {
                    sender.sendMessage(NO_PLAYER_SPECIFIED);
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.lastseen");
        }
    }

    public static class IpCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        IpCommand(RedisVelocity plugin) {
            //super("ip", "redisbungee.command.ip", "playerip", "rip", "rplayerip");
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                if (args.length > 0) {
                    UUID uuid = plugin.getUuidTranslator().getTranslatedUuid(args[0], true);

                    if (uuid == null) {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                        return;
                    }

                    InetAddress ia = RedisVelocity.getApi().getPlayerIp(uuid);

                    if (ia != null) {
                        TextComponent message = Component.text(args[0] + " is connected from " + ia.toString() + ".", NamedTextColor.GREEN);
                        sender.sendMessage(message);
                    } else {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                    }
                } else {
                    sender.sendMessage(NO_PLAYER_SPECIFIED);
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.ip");
        }
    }

    public static class PlayerProxyCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        PlayerProxyCommand(RedisVelocity plugin) {
            //super("pproxy", "redisbungee.command.pproxy");
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                if (args.length > 0) {
                    UUID uuid = plugin.getUuidTranslator().getTranslatedUuid(args[0], true);

                    if (uuid == null) {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                        return;
                    }

                    String proxy = RedisVelocity.getApi().getProxy(uuid);

                    if (proxy != null) {
                        TextComponent message = Component.text(args[0] + " is connected to " + proxy + ".", NamedTextColor.GREEN);
                        sender.sendMessage(message);
                    } else {
                        sender.sendMessage(PLAYER_NOT_FOUND);
                    }
                } else {
                    sender.sendMessage(NO_PLAYER_SPECIFIED);
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.pproxy");
        }
    }

    public static class SendToAll implements SimpleCommand {
        private final RedisVelocity plugin;

        SendToAll(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            String[] args = invocation.arguments();
            CommandSource sender = invocation.source();
            if (args.length > 0) {
                String command = Joiner.on(" ").skipNulls().join(args);
                RedisVelocity.getApi().sendProxyCommand(command);
                TextComponent message = Component.text("Sent the command /" + command + " to all proxies.", NamedTextColor.GREEN);
                sender.sendMessage(message);
            } else {
                sender.sendMessage(NO_COMMAND_SPECIFIED);
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.sendtoall");
        }
    }

    public static class ServerId implements SimpleCommand {
        private final RedisVelocity plugin;

        ServerId(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(Invocation invocation) {
            invocation.source().sendMessage(Component.text("You are on " + RedisVelocity.getApi().getServerId() + ".", NamedTextColor.YELLOW));
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.serverid");
        }
    }

    public static class ServerIds implements SimpleCommand {
        public ServerIds() {
        }

        @Override
        public void execute(Invocation invocation) {
            invocation.source().sendMessage(
                Component.text("All server IDs: " + Joiner.on(", ").join(RedisVelocity.getApi().getAllServers()), NamedTextColor.YELLOW));
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.serverids");
        }
    }

    public static class PlistCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        PlistCommand(RedisVelocity plugin) {
            //super("plist", "redisbungee.command.plist", "rplist");
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();
            plugin.getProxy().getScheduler().buildTask(plugin, () -> {
                String proxy = args.length >= 1 ? args[0] : RedisVelocity.getConfiguration().getServerId();

                if (!plugin.getServerIds().contains(proxy)) {
                    sender.sendMessage(Component.text(proxy + " is not a valid proxy. See /serverids for valid proxies.", NamedTextColor.RED));
                    return;
                }

                Set<UUID> players = RedisVelocity.getApi().getPlayersOnProxy(proxy);
                Component playersOnline = Component.text(playerPlural(players.size()) + " currently on proxy " + proxy + ".", NamedTextColor.YELLOW);

                if (args.length >= 2 && args[1].equals("showall")) {
                    Multimap<String, UUID> serverToPlayers = RedisVelocity.getApi().getServerToPlayers();
                    Multimap<String, String> human = HashMultimap.create();

                    for (Map.Entry<String, UUID> entry : serverToPlayers.entries()) {
                        if (players.contains(entry.getValue())) {
                            human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                        }
                    }

                    for (String server : new TreeSet<>(human.keySet())) {
                        TextComponent serverName = Component.text("[" + server + "] ", NamedTextColor.RED);
                        TextComponent serverCount = Component.text("(" + human.get(server).size() + "): ", NamedTextColor.YELLOW);
                        TextComponent serverPlayers = Component.text(Joiner.on(", ").join(human.get(server)), NamedTextColor.WHITE);
                        sender.sendMessage(Component.textOfChildren(serverName, serverCount, serverPlayers));
                    }

                    sender.sendMessage(playersOnline);
                } else {
                    sender.sendMessage(playersOnline);
                    sender.sendMessage(Component.text("To see all players online, use /plist " + proxy + " showall.", NamedTextColor.YELLOW));
                }
            }).schedule();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.plist");
        }
    }

    public static class DebugCommand implements SimpleCommand {
        private final RedisVelocity plugin;

        DebugCommand(RedisVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(final Invocation invocation) {
            CommandSource sender = invocation.source();
            TextComponent poolActiveStat = Component.text("Currently active pool objects: " + plugin.getPool().getNumActive());
            TextComponent poolIdleStat = Component.text("Currently idle pool objects: " + plugin.getPool().getNumIdle());
            TextComponent poolWaitingStat = Component.text("Waiting on free objects: " + plugin.getPool().getNumWaiters());
            sender.sendMessage(Component.text("ID: " + RedisVelocity.getApi().getServerId()));
            sender.sendMessage(poolActiveStat);
            sender.sendMessage(poolIdleStat);
            sender.sendMessage(poolWaitingStat);
            sender.sendMessage(Component.text("Last heartbeat: " + (System.currentTimeMillis() - plugin.lastHeartbeat) + "ms ago"));
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("redisbungee.command.debug");
        }
    }
}
