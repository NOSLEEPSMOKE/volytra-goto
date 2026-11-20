package dev.volytragoto.api;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.client.option.KeyBinding;

/** What the Volytra Goto module asks of its flight logic ({@code dev.volytragoto.brain.BrainImpl}). */
public interface Brain {
    /** The module was turned on. */
    void activate();

    void deactivate();

    void tick(TickEvent.Pre event);

    void move(PlayerMoveEvent event);

    void packet(PacketEvent.Receive event);

    void render(Render3DEvent event);

    /** Should VolytraFly treat this key as physically held? */
    boolean holdingKey(KeyBinding key);

    String infoString();

    String statusLine();

    /** Start or redirect a goto (y == null: land). */
    void go(int x, Integer y, int z);
}
