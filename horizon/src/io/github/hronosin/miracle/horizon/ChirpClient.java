package io.github.hronosin.miracle.horizon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/** Chirp's client half, apart so dedicated servers (which have no Minecraft class) never load it. */
final class ChirpClient {

    private ChirpClient() {
    }

    static void play(SoundEvent event, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
}
