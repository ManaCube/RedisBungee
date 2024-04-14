package com.imaginarycode.minecraft.redisbungee.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.kyori.adventure.permission.PermissionChecker;

/**
 * This class is the CommandSender that RedisBungee uses to dispatch commands to BungeeCord.
 * <p>
 * It inherits all permissions of the console command sender. Sending messages and modifying permissions are no-ops.
 *
 * @author tuxed
 * @since 0.2.3
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RedisBungeeCommandSender implements CommandSource {
    private static final RedisBungeeCommandSender singleton;
    private final PermissionChecker permissionChecker = PermissionChecker.always(net.kyori.adventure.util.TriState.TRUE);

    static {
        singleton = new RedisBungeeCommandSender();
    }

    public static RedisBungeeCommandSender getSingleton() {
        return singleton;
    }


    @Override
    public boolean hasPermission(String permission) {
        return this.permissionChecker.test(permission);
    }

    @Override
    public Tristate getPermissionValue(String s) {
        return Tristate.TRUE;
    }

    @Override
    public PermissionChecker getPermissionChecker() {
        return this.permissionChecker;
    }
}