package top.xuyangjerry.mcmod.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import top.xuyangjerry.mcmod.client.screen.LightChatPatchConfigScreen;

public class LcpModMenuApiImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LightChatPatchConfigScreen::new;
    }
}
