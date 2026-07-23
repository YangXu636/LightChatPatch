package top.xuyangjerry.mcmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuyangjerry.mcmod.config.LcpConfig;

public final class LightChatPatch {
    public static final String MOD_ID = "light_chat_patch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LcpConfig.load();
    }
}
