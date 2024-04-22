package com.imaginarycode.minecraft.redisbungee.velocity.events;

import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * This event is posted when a PubSub message is received.
 * <p>
 * <strong>Warning</strong>: This event is fired in a separate thread!
 *
 * @since 0.2.6
 */
@RequiredArgsConstructor
@ToString
public class PubSubMessageEvent {
    private final String channel;
    private final String message;

    public String getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }
}
