package top.xuyangjerry.mcmod.fabric;

import net.fabricmc.api.ModInitializer;

import top.xuyangjerry.mcmod.LightChatPatch;

public final class ExampleModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        LightChatPatch.init();
    }
}
