package top.xuyangjerry.mcmod;

import top.xuyangjerry.mcmod.config.LcpConfig;

public final class LightChatPatch {
    public static final String MOD_ID = "light_chat_patch";

    public static void init() {
        LcpConfig.load();
    }
}
